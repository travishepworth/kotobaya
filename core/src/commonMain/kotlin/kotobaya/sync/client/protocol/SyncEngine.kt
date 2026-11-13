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
    /**
     * Ops applied per local transaction when applying a pull page (BUG-8). A page is applied in chunks
     * of at most this many ops, each committed with the cursor at its last op (so a chunk is exactly
     * a smaller page as far as SYNC.md 6.1 is concerned), so the writer never holds the database for
     * a whole page.
     */
    private val pullChunkOps: Int = DEFAULT_PULL_CHUNK_OPS,
    /**
     * Called after every committed pull chunk (outside any transaction), e.g. to publish progress and
     * to yield the thread; it may suspend. A [kotlin.coroutines.cancellation.CancellationException] it
     * throws ends [pullAll] (everything committed so far stays; the next cycle resumes at `pull_seq`).
     */
    private val onPullProgress: suspend (PullProgress) -> Unit = {},
) {
    init {
        require(pullChunkOps >= 1) { "pullChunkOps must be at least 1" }
    }

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
        val done = pull as CycleResult.Completed
        return CycleResult.Completed(push.pushedOps, done.pulledOps, purged, done.pullAdvanced)
    }

    /**
     * The log reset of SYNC.md 6.2 for a [CycleResult.LogChanged], in one transaction: every `acked`
     * row becomes `pending` (`acked_seq = NULL`, `attempts = 0`); `rejected` rows with code
     * `unknown_reference` get `attempts = 0` (the auto-retry count, 6.2; the retry itself is T303b's);
     * store the new `log_epoch` and `durable_seq`; set `rebuild_pending = "1"`; set `pull_seq = "0"`
     * (decided for C-10: with the old `pull_seq` the rebuild's step-0 push would see
     * `high_water_seq < pull_seq` and report the change again forever; nothing is pulled or purged
     * while `rebuild_pending` is set, and rebuild step 1 sets it to 0 anyway); record
     * [kotobaya.sync.client.store.SyncStateKeys.LOG_RESET_EPOCH]. Restartable: running it twice for
     * the same change gives the same state. The rebuild (10.3) is T303b's.
     */
    public fun resetLog(changed: CycleResult.LogChanged): Unit = store.transaction { tx ->
        val bk = tx.bookkeeping
        for (row in tx.outbox.inState(PendingState.ACKED)) tx.outbox.markPending(row.opId, 0)
        for (row in tx.outbox.inState(PendingState.REJECTED)) {
            if (row.lastError?.startsWith("$UNKNOWN_REFERENCE:") == true) tx.outbox.setAttempts(row.opId, 0)
        }
        bk.logEpoch = changed.newEpoch
        bk.durableSeq = changed.durableSeq
        bk.pullSeq = 0
        bk.rebuildPending = true
        bk.logResetEpoch = changed.newEpoch
        bk.upgradeRequired = null
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
                    cap = SyncLimits.MAX_PUSH_OPS // a 413 halving lasts until a batch gets through
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
     * Pull (SYNC.md 6.1) from `pull_seq` until `has_more` is false. Each page is applied in chunks of
     * at most [pullChunkOps] ops, one transaction per chunk, each committing the ops, `pull_seq` = the
     * last applied op's `seq` (the page's `next_since` after its last chunk), `hlc_last` and the log
     * info: a chunk is a smaller page, so a process killed between chunks resumes after the last
     * committed one. Returns [CycleResult.Completed] (with `pushedOps = 0`), [CycleResult.LogChanged]
     * (page discarded; checked before its first chunk), [CycleResult.UpgradeRequired] or
     * [CycleResult.Failed]. Does not look at `rebuild_pending`, so the rebuild (T303b) uses it to pull
     * from 0.
     */
    public suspend fun pullAll(): CycleResult {
        var pulled = 0
        val initial = store.transaction { it.bookkeeping.pullSeq }
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
            val chunks = page.ops.chunked(pullChunkOps).ifEmpty { listOf(emptyList()) }
            var cursor = since
            for ((i, chunk) in chunks.withIndex()) {
                val last = i == chunks.lastIndex
                val outcome = try {
                    store.transaction { tx -> applier.batch(tx) { applyChunk(tx, page, chunk, first = i == 0, last = last) } }
                } catch (e: ProtocolException) {
                    return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PULL, e.message ?: "bad pull page", 200)
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    return CycleResult.Failed(FailureKind.LOCAL_APPLY, SyncStep.PULL, e.message ?: e.toString())
                }
                when (outcome) {
                    is PageOutcome.Stop -> return outcome.result
                    is PageOutcome.Applied -> {
                        pulled += outcome.count
                        cursor = outcome.cursor
                    }
                }
                onPullProgress(PullProgress(pulled, cursor - initial, maxOf(page.log.highWaterSeq - initial, cursor - initial)))
            }
            if (!page.hasMore) return CycleResult.Completed(0, pulled, 0, cursor > initial)
            if (cursor <= since) {
                return CycleResult.Failed(FailureKind.PROTOCOL, SyncStep.PULL, "has_more without progress at since=$since", 200)
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
        bk.recordServerSkew(resp.log.serverTime - pt)
        if (changed == null) storeLogInfo(bk, resp.log)
        return Recorded(changed, missing)
    }

    /** Throws [ProtocolException] unless the integer `seq`s of [page] are strictly ascending. */
    private fun checkAscending(page: WirePullPage) {
        var prev = Long.MIN_VALUE
        for (raw in page.ops) {
            val s = Wire.seqOf(raw) ?: continue
            if (s <= prev) throw ProtocolException("pull page seqs are not strictly ascending ($prev then $s)")
            prev = s
        }
    }

    /**
     * SYNC.md 6.1 client procedure for [ops], one chunk of [page], inside its transaction: the log
     * identity check (6.2) and the page's `seq` order on the [first] chunk; each op observed and applied; `pull_seq` set to the
     * last applied `seq` (to `next_since` after the [last] chunk); `hlc_last`, server skew and log info
     * stored. The upgrade-required stop is cleared only after the last chunk of the last page.
     */
    private fun applyChunk(tx: StoreTransaction<L>, page: WirePullPage, ops: List<kotobaya.contracts.sync.JsonValue>, first: Boolean, last: Boolean): PageOutcome {
        val bk = tx.bookkeeping
        if (first) {
            logChange(bk, page.log, SyncStep.PULL)?.let { return PageOutcome.Stop(it) }
            checkAscending(page) // the whole page, before its first op is applied
        }
        val start = bk.pullSeq
        var cursor = start
        var state = hlcRules.stateOf(bk.hlcLast)
        var lastHlc: HlcString? = bk.hlcLast
        var count = 0
        var stop: CycleResult.UpgradeRequired? = null
        for (raw in ops) {
            val rawSeq = Wire.seqOf(raw)
            if (rawSeq != null && rawSeq <= cursor) continue // already applied: a replayed or overlapping page
            when (val d = codec.decodeTree(raw, OpSource.PULLED)) {
                is OpDecodeResult.Known -> {
                    val op = d.op
                    val observed = hlcRules.observe(state, op.hlc)
                    if (observed != state) {
                        state = observed
                        lastHlc = op.hlc
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
        if (stop == null && last && page.nextSince > cursor) cursor = page.nextSince
        if (cursor > start) bk.pullSeq = cursor
        if (lastHlc != bk.hlcLast) bk.hlcLast = lastHlc
        bk.recordServerSkew(page.log.serverTime - clock.nowMs())
        storeLogInfo(bk, page.log)
        if (stop != null) {
            bk.upgradeRequired = UpgradeRequiredState.of(stop).encode()
            return PageOutcome.Stop(stop)
        }
        if (last && !page.hasMore) bk.upgradeRequired = null // the pull got past every op it had stopped at
        return PageOutcome.Applied(count, cursor)
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

    /** Constants. */
    public companion object {
        /** Default [pullChunkOps]. */
        public const val DEFAULT_PULL_CHUNK_OPS: Int = 200

        private const val HTTP_TOO_LARGE = 413
        private const val STATUS_REJECTED = "rejected"
        private const val CLOCK_AHEAD = "clock_ahead"
        private const val CLOCK_BEHIND = "clock_behind"
        private const val UNKNOWN_REFERENCE = "unknown_reference"
    }
}

/**
 * Progress of [SyncEngine.pullAll] after a committed chunk, in `seq` units from where the pull started
 * (`seq` may have gaps, so these are an estimate of ops): [done] of [total], [total] being the server's
 * `high_water_seq` at the last page. [pulledOps] counts the ops actually applied so far.
 */
public data class PullProgress(
    /** Ops applied so far in this pull. */
    public val pulledOps: Int,
    /** `pull_seq` now minus `pull_seq` when the pull started. */
    public val done: Long,
    /** `high_water_seq` of the last page minus `pull_seq` when the pull started (never below [done]). */
    public val total: Long,
)
