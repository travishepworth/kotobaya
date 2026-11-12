package kotobaya.sync.client.protocol

import kotobaya.contracts.HlcString
import kotobaya.contracts.sync.HlcRules
import kotobaya.contracts.sync.OpCodec
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncBookkeeping
import kotobaya.sync.client.store.bookkeeping

/**
 * The device's sync protocol (SYNC.md 5, 6.1, 6.2 detection, 10.1, 10.5). Every store change happens
 * in a [DeviceStore] transaction that never spans a network call; every step is restartable, so a
 * process killed at any point loses nothing (rows are `pending` or `acked`, never missing) and the
 * next cycle converges.
 *
 * Not done here: the log reset and the rebuild (T303b act on [CycleResult.LogChanged] /
 * [CycleResult.RebuildPending]), user actions on rejected rows (T303d), scheduling (T303c).
 */
public class SyncEngine<L>(
    private val store: DeviceStore<L>,
    private val transport: SyncTransport,
    private val applier: OpApplier<L>,
    private val clock: Clock,
    private val hlcRules: HlcRules = DefaultHlcRules,
    private val codec: OpCodec = DefaultOpCodec,
) {
    /**
     * Sync cycle (SYNC.md 6.1): refresh the `/meta` limits; push until no row is `pending`; if
     * `rebuild_pending` is set stop with [CycleResult.RebuildPending]; otherwise pull until
     * `has_more` is false; purge the `acked` rows allowed by 5.4; set `last_sync_at`.
     */
    public suspend fun cycle(): CycleResult {
        transport.limits()?.let { limits -> store.transaction { SyncLimits.write(it.bookkeeping, limits) } }
        val push = pushAll()
        if (push !is CycleResult.Completed) return push
        if (store.transaction { it.bookkeeping.rebuildPending }) return CycleResult.RebuildPending(push.pushedOps)
        val pull = pullAll()
        if (pull is CycleResult.LogChanged || pull is CycleResult.Failed) return pull
        val purged = purge()
        if (pull is CycleResult.UpgradeRequired) return pull
        store.transaction { it.bookkeeping.lastSyncAt = clock.nowMs() }
        return CycleResult.Completed(push.pushedOps, (pull as CycleResult.Completed).pulledOps, purged)
    }

    /**
     * Push (SYNC.md 5.1, 5.4): every `pending` row in ascending hlc, in batches of at most 500 ops
     * whose body fits `max_push_bytes`, until none is left. Returns [CycleResult.Completed] (with
     * `pulledOps = 0`), [CycleResult.LogChanged] (results recorded) or [CycleResult.Failed].
     * Allowed while `rebuild_pending` is set (10.3 step 0).
     */
    public suspend fun pushAll(): CycleResult {
        var cap = SyncLimits.MAX_PUSH_OPS
        var pushed = 0
        while (true) {
            val (limits, rows) = store.transaction { tx ->
                SyncLimits.read(tx.bookkeeping) to tx.outbox.inState(PendingState.PENDING, cap)
            }
            if (rows.isEmpty()) return CycleResult.Completed(pushed, 0, 0)
            val batch = batchOf(rows, limits.maxPushBytes)
            val ids = batch.map { it.first.opId }
            when (val r = transport.push(Wire.body(batch.map { it.second }))) {
                is TransportResponse.Ok -> {
                    val parsed = try {
                        Wire.parsePush(r.body)
                    } catch (e: ProtocolException) {
                        bump(ids)
                        return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PUSH, e.message ?: "bad push response", 200)
                    }
                    val recorded = store.transaction { tx -> record(tx, batch.map { it.first }, parsed) }
                    pushed += batch.size - recorded.missing
                    recorded.changed?.let { return it }
                    if (recorded.missing > 0) {
                        return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PUSH, "${recorded.missing} pushed ops got no result", 200)
                    }
                }
                is TransportResponse.HttpError -> when {
                    r.status == HTTP_TOO_LARGE && batch.size > 1 -> cap = (batch.size + 1) / 2
                    r.status == HTTP_TOO_LARGE -> {
                        // a single op still too large: rejected by the client itself (5.4); never backoff
                        store.transaction { tx ->
                            tx.outbox.markRejected(ids.single(), "op_too_large: HTTP 413 for this op pushed alone (${r.message})")
                        }
                        pushed += 1
                    }
                    r.status == 401 || r.status == 403 ->
                        return CycleResult.Failed(FailureKind.AUTH, SyncStep.PUSH, r.message, r.status)
                    else -> {
                        bump(ids)
                        val kind = if (r.status == 400) FailureKind.BAD_REQUEST else FailureKind.SERVER_ERROR
                        return CycleResult.Failed(kind, SyncStep.PUSH, r.message, r.status)
                    }
                }
                is TransportResponse.NetworkError -> {
                    bump(ids)
                    return CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PUSH, r.message)
                }
            }
        }
    }

    /**
     * Pull (SYNC.md 6.1) from `pull_seq` until `has_more` is false, one transaction per page. Returns
     * [CycleResult.Completed] (with `pushedOps = 0`), [CycleResult.LogChanged] (page discarded),
     * [CycleResult.UpgradeRequired] or [CycleResult.Failed]. Does not look at `rebuild_pending`, so the
     * rebuild (T303b) uses it to pull from 0.
     */
    public suspend fun pullAll(): CycleResult {
        var pulled = 0
        while (true) {
            val since = store.transaction { it.bookkeeping.pullSeq }
            val page = when (val r = transport.pull(since)) {
                is TransportResponse.Ok -> try {
                    Wire.parsePull(r.body)
                } catch (e: ProtocolException) {
                    return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PULL, e.message ?: "bad pull page", 200)
                }
                is TransportResponse.HttpError -> {
                    val kind = if (r.status == 401 || r.status == 403) FailureKind.AUTH else FailureKind.SERVER_ERROR
                    return CycleResult.Failed(kind, SyncStep.PULL, r.message, r.status)
                }
                is TransportResponse.NetworkError -> return CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PULL, r.message)
            }
            val outcome = try {
                store.transaction { tx -> applyPage(tx, page) }
            } catch (e: ProtocolException) {
                return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PULL, e.message ?: "bad pull page", 200)
            } catch (e: Exception) {
                return CycleResult.Failed(FailureKind.LOCAL_APPLY, SyncStep.PULL, e.message ?: e.toString())
            }
            when (outcome) {
                is PageOutcome.Stop -> return outcome.result
                is PageOutcome.Applied -> {
                    pulled += outcome.count
                    if (!page.hasMore) return CycleResult.Completed(0, pulled, 0)
                    if (outcome.cursor <= since) {
                        return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PULL, "has_more without progress at since=$since", 200)
                    }
                }
            }
        }
    }

    /**
     * Deletes the `acked` rows with `acked_seq <= pull_seq` and `acked_seq <= durable_seq` (SYNC.md
     * 5.4; both values belong to the stored `log_epoch`). Returns how many.
     */
    public fun purge(): Int = store.transaction { tx ->
        val bk = tx.bookkeeping
        tx.outbox.deleteAckedThrough(minOf(bk.pullSeq, bk.durableSeq))
    }

    // ---------------------------------------------------------------------------------------------

    private class Recorded(val changed: CycleResult.LogChanged?, val missing: Int)

    private sealed interface PageOutcome {
        class Applied(val count: Int, val cursor: Long) : PageOutcome

        class Stop(val result: CycleResult) : PageOutcome
    }

    /** The longest prefix of [rows] whose body fits [maxPushBytes] (always at least one row). */
    private fun batchOf(rows: List<PendingOpRow>, maxPushBytes: Long): List<Pair<PendingOpRow, String>> {
        val out = ArrayList<Pair<PendingOpRow, String>>()
        var size = Wire.BODY_OVERHEAD
        for (row in rows) {
            val element = Wire.element(row)
            val add = DefaultCanonicalJson.utf8Size(element) + if (out.isEmpty()) 0 else 1
            if (out.isNotEmpty() && size + add > maxPushBytes) break
            out.add(row to element)
            size += add
        }
        return out
    }

    private fun bump(ids: List<String>) = store.transaction { it.outbox.incrementAttempts(ids) }

    /** SYNC.md 5.4: record each result; 6.2: compare the log identity first. */
    private fun record(tx: StoreTransaction<L>, batch: List<PendingOpRow>, resp: WirePushResponse): Recorded {
        val bk = tx.bookkeeping
        val pt = clock.nowMs()
        val changed = logChange(bk, resp.log, SyncStep.PUSH)
        val byId = HashMap<String, WireResult>()
        for (r in resp.results) if (r.opId !in byId) byId[r.opId] = r
        var missing = 0
        val noResult = ArrayList<String>()
        for (row in batch) {
            val r = byId[row.opId]
            if (r == null) {
                missing++
                noResult.add(row.opId)
                continue
            }
            val seq = r.seq
            if (r.status != STATUS_REJECTED && seq != null) {
                tx.outbox.markAcked(row.opId, seq)
                val acked = bk.hlcAcked
                if (acked == null || hlcRules.compare(row.hlc, acked) > 0) bk.hlcAcked = row.hlc
            } else {
                val code = r.code ?: "unknown"
                tx.outbox.markRejected(row.opId, "$code: ${r.message ?: ""}")
                if ((code == CLOCK_AHEAD || code == CLOCK_BEHIND) && bk.remintOffset == null) {
                    bk.remintOffset = resp.log.serverTime - pt
                }
            }
        }
        if (noResult.isNotEmpty()) tx.outbox.incrementAttempts(noResult)
        bk.serverSkewMs = resp.log.serverTime - pt
        if (changed == null) storeLogInfo(bk, resp.log)
        return Recorded(changed, missing)
    }

    /** SYNC.md 6.1 client procedure for one page, inside its transaction. */
    private fun applyPage(tx: StoreTransaction<L>, page: WirePullPage): PageOutcome {
        val bk = tx.bookkeeping
        logChange(bk, page.log, SyncStep.PULL)?.let { return PageOutcome.Stop(it) }
        var prev = Long.MIN_VALUE
        for (raw in page.ops) {
            val s = Wire.seqOf(raw) ?: continue
            if (s <= prev) throw ProtocolException("pull page seqs are not strictly ascending ($prev then $s)")
            prev = s
        }
        val start = bk.pullSeq
        var cursor = start
        var state = hlcRules.stateOf(bk.hlcLast)
        var last: HlcString? = bk.hlcLast
        var count = 0
        var stop: CycleResult.UpgradeRequired? = null
        for (raw in page.ops) {
            val rawSeq = Wire.seqOf(raw)
            if (rawSeq != null && rawSeq <= cursor) continue // already applied: a replayed or overlapping page
            when (val d = codec.decodeTree(raw, OpSource.PULLED)) {
                is OpDecodeResult.Known -> {
                    val op = d.op
                    val observed = hlcRules.observe(state, op.hlc)
                    if (observed != state) {
                        state = observed
                        last = op.hlc
                    }
                    if (op.opType.deviceApplies) applier.apply(tx, op, ApplyOrigin.PULLED)
                    cursor = op.seq ?: cursor
                    count++
                }
                is OpDecodeResult.Unsupported -> stop = CycleResult.UpgradeRequired(
                    d.seq, Wire.stringMember(raw, "op_id"), d.opType, d.opVersion, true,
                    "unsupported op ${d.opType} version ${d.opVersion}",
                )
                is OpDecodeResult.Invalid -> stop = CycleResult.UpgradeRequired(
                    d.seq ?: rawSeq, Wire.stringMember(raw, "op_id"), Wire.stringMember(raw, "op_type"), null, false,
                    "${d.message} at '${d.pointer}'",
                )
            }
            if (stop != null) break
        }
        if (stop == null && page.nextSince > cursor) cursor = page.nextSince
        if (cursor > start) bk.pullSeq = cursor
        if (last != bk.hlcLast) bk.hlcLast = last
        bk.serverSkewMs = page.log.serverTime - clock.nowMs()
        storeLogInfo(bk, page.log)
        return if (stop != null) PageOutcome.Stop(stop) else PageOutcome.Applied(count, cursor)
    }

    /** SYNC.md 6.2 "Device, on every response": null = same log (or none stored yet). */
    private fun logChange(bk: SyncBookkeeping, log: LogInfo, step: SyncStep): CycleResult.LogChanged? {
        val stored = bk.logEpoch ?: return null
        val pullSeq = bk.pullSeq
        if (log.logEpoch == stored && log.highWaterSeq >= pullSeq) return null
        return CycleResult.LogChanged(step, stored, pullSeq, log.logEpoch, log.highWaterSeq, log.durableSeq)
    }

    /** Same log: store the epoch if new, `durable_seq` monotonically (it never decreases within an epoch). */
    private fun storeLogInfo(bk: SyncBookkeeping, log: LogInfo) {
        if (bk.logEpoch == null) {
            bk.logEpoch = log.logEpoch
            bk.durableSeq = log.durableSeq
        } else if (log.durableSeq > bk.durableSeq) {
            bk.durableSeq = log.durableSeq
        }
    }

    private companion object {
        const val HTTP_TOO_LARGE = 413
        const val STATUS_REJECTED = "rejected"
        const val CLOCK_AHEAD = "clock_ahead"
        const val CLOCK_BEHIND = "clock_behind"
    }
}
