package kotobaya.sync.fuzz

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.auth.Principal
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.PushOutcome
import kotobaya.sync.SyncModule
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** The real server (a [SyncModule] on Postgres) as the simulated devices see it: push, pull, its wall clock [pt]. */
class SimServer(var module: SyncModule, private val now: AtomicReference<Instant>, private val principal: (String) -> Principal) {
    var pt: Long
        get() = now.get().toEpochMilli()
        set(v) = now.set(Instant.ofEpochMilli(v))

    /** `POST /sync/push` as device [deviceId]; the `PushResponse` as JSON. */
    fun push(deviceId: String, ops: List<JsonNode>): JsonNode {
        val body = json.writeValueAsString(mapOf("ops" to ops))
        return when (val o = module.push.push(body, principal(deviceId))) {
            is PushOutcome.Ok -> json.valueToTree(o.body)
            is PushOutcome.BadRequest -> error("push was a 400: ${o.message}")
        }
    }

    /** `GET /sync/pull?since=` ; the `PullResponse` as JSON. */
    fun pull(since: Long, limit: Int = 500): JsonNode = json.readTree(module.pull.pull(since, limit, null).text)
}

/** SYNC.md 3.2 `mint()` / `mint_at()` on full HLC strings ("" = (0, 0)). */
fun hlcMint(last: String, pt: Long, device: String): String {
    var ms = if (last.isEmpty()) 0L else last.substring(0, 12).toLong(16)
    var ctr = if (last.isEmpty()) 0 else last.substring(13, 17).toInt(16)
    when {
        pt > ms -> { ms = pt; ctr = 0 }
        ctr < 0xffff -> ctr++
        else -> { ms++; ctr = 0 }
    }
    return "%012x-%04x-%s".format(ms, ctr, device)
}

fun hlcObserve(last: String, h: String): String = if (last.isEmpty() || h.substring(0, 17) > last.substring(0, 17)) h else last

/** The re-mint shift of SYNC.md 10.4 step 5 (port of `model.remint_times`). Items: (t, lower bound). */
fun remintTimes(items: List<Pair<Long, Long>>, offset: Long, now: Long): Pair<Long, List<Long>> {
    val low = items.map { (_, l) -> minOf(l, now) }
    val dHi = now - items.maxOf { it.first }
    val dLo = items.indices.maxOf { low[it] - items[it].first }
    val delta = minOf(maxOf(offset, dLo), dHi)
    return delta to items.indices.map { i -> minOf(maxOf(items[i].first + delta, low[i]), now) }
}

/**
 * A device with its outbox and sync procedure: a Kotlin port of `contracts/sync/tools/model.py`
 * `Device` (SYNC.md 4, 5.4, 6.1, 6.2 log reset, 10.3 rebuild, 10.4 re-mint), on a [RefReplica].
 * Test code: the real device side is Phase 3.
 */
class SimDevice(val id: String, private val guard: Boolean = true) {
    class Row(val op: ObjectNode, var state: String = "pending", var ackedSeq: Long? = null, var lastError: String? = null)

    var r = RefReplica()
    val outbox = mutableListOf<Row>()
    var pullSeq = 0L
    var logEpoch: String? = null
    var durableSeq = 0L
    var hlcLast = ""
    var hlcAcked = ""
    var rebuildPending = false
    var remintOffset: Long? = null
    var lastSkew: Long? = null
    var pt = 0L
    private var n = 0

    fun newId(kind: Int): String {
        n++
        return "0193a1b2-%04x-7000-8000-%s%08x".format(kind, id.takeLast(4), n)
    }

    fun clockGuardOk(): Boolean = pt >= Reserved.CLOCK_FLOOR_MS && (hlcLast.isEmpty() || pt >= wallOf(hlcLast) - Reserved.DAY_MS)

    /** SYNC.md 4: mint, apply locally, queue. [at] = the virtual pt of `mint_at` (re-mint only). */
    fun make(type: OpType, payload: Map<String, Any?>, at: Long? = null): ObjectNode {
        check(!rebuildPending) { "no minting while a rebuild is pending" }
        if (guard && !clockGuardOk()) throw IllegalStateException("fix your clock: minting refused (SYNC.md 3.4 G1/G2)")
        hlcLast = hlcMint(hlcLast, at ?: pt, id)
        val p = json.valueToTree<ObjectNode>(payload)
        val op = json.createObjectNode().apply {
            put("op_id", newId(0xff))
            put("op_type", type.wire)
            put("op_version", 1)
            put("entity_type", type.entityType)
            put("entity_id", DefaultOpCodec.entityIdOf(type, DefaultCanonicalJson.decode(json.writeValueAsString(p)).value as JsonObject))
            put("device_id", id)
            put("hlc", hlcLast)
            set<JsonNode>("payload", p)
        }
        r.apply(op)
        outbox += Row(op)
        return op
    }

    private fun logChanged(resp: JsonNode): Boolean {
        if (logEpoch == null) logEpoch = resp["log_epoch"].asText()
        return resp["log_epoch"].asText() != logEpoch || resp["high_water_seq"].asLong() < pullSeq
    }

    private fun startLogReset(resp: JsonNode) {
        for (row in outbox) if (row.state == "acked") { row.state = "pending"; row.ackedSeq = null }
        logEpoch = resp["log_epoch"].asText()
        durableSeq = resp["durable_seq"].asLong()
        rebuildPending = true
    }

    fun push(server: SimServer) {
        while (true) {
            val batch = outbox.filter { it.state == "pending" }.sortedBy { it.op["hlc"].asText() }.take(500)
            if (batch.isEmpty()) return
            val resp = server.push(id, batch.map { it.op })
            lastSkew = resp["server_time"].asLong() - pt
            for ((row, res) in batch.zip(resp["results"])) {
                check(res["op_id"].asText() == row.op["op_id"].asText())
                if (res["status"].asText() == "rejected") {
                    row.state = "rejected"; row.lastError = res["error"]["code"].asText()
                    if (row.lastError in CLOCK_CODES && remintOffset == null) remintOffset = lastSkew
                } else {
                    row.state = "acked"; row.ackedSeq = res["seq"].asLong()
                    if (row.op["hlc"].asText() > hlcAcked) hlcAcked = row.op["hlc"].asText()
                }
            }
            if (logChanged(resp)) {
                startLogReset(resp)
                continue
            }
            durableSeq = resp["durable_seq"].asLong()
        }
    }

    /** Returns false if the pull stopped because the log changed identity. */
    fun pull(server: SimServer): Boolean {
        while (true) {
            val resp = server.pull(pullSeq)
            lastSkew = resp["server_time"].asLong() - pt
            if (logChanged(resp)) {
                startLogReset(resp)
                return false
            }
            for (op in resp["ops"]) {
                hlcLast = hlcObserve(hlcLast, op["hlc"].asText())
                try {
                    r.apply(op)
                } catch (e: MissingRow) {
                    // 7.7: cannot happen in seq order
                }
            }
            pullSeq = resp["next_since"].asLong()
            durableSeq = resp["durable_seq"].asLong()
            if (!resp["has_more"].asBoolean()) return true
        }
    }

    fun gc() {
        val limit = minOf(pullSeq, durableSeq)
        outbox.removeAll { it.state == "acked" && (it.ackedSeq ?: Long.MAX_VALUE) <= limit }
    }

    fun rebuild(server: SimServer) {
        while (true) {
            push(server)
            r = RefReplica(); pullSeq = 0
            if (pull(server)) break
        }
        for (row in outbox.filter { it.state == "pending" || it.state == "rejected" || (it.state == "acked" && (it.ackedSeq ?: 0) > pullSeq) }
            .sortedBy { it.op["hlc"].asText() }) {
            try {
                r.apply(row.op)
            } catch (e: MissingRow) {
                // the op has no effect after the rebuild (7.7)
            }
        }
        rebuildPending = false
    }

    /**
     * Step 1 of Re-mint (SYNC.md 10.4, C-8): a port of `model.remint_select`. [rows] = the outbox rows in
     * state `rejected`, any order. [Selection.e] is in step-1 order (window-derived ops after their owner
     * note); [Selection.payloads] = op id → the payloads step 6a mints for each op of E, in order.
     */
    class Selection(
        val c: List<Row>, val e: List<Row>, val r: List<Row>, val u: List<Row>, val d: List<Row>,
        val w: Set<String>, val payloads: Map<String, List<ObjectNode>>,
    )

    /** The step-1 selection of the last [remint] (for the fuzz's coverage counts). */
    var lastSelection: Selection? = null

    /** SYNC.md 10.4 Re-mint, one local transaction (a port of `model.Device.remint`); the rebuild runs in the next sync. */
    fun remint() {
        check(outbox.none { it.state == "pending" }) { "push until nothing is pending" }
        check(lastSkew != null && Math.abs(lastSkew!!) <= 300_000) { "clock must be correct" }
        val sel = remintSelect(outbox.filter { it.state == "rejected" })
        lastSelection = sel
        val reviewIds = sel.r.map { it.op["payload"]["id"].asText() }.toSet()
        val u = sel.u + outbox.filter { it.op["op_type"].asText() == "review.undone" && it.op["payload"]["review_id"].asText() in reviewIds && it !in sel.u }
        val drop = (sel.c + sel.d + sel.e + sel.r + u).toSet()
        val savedOutbox = outbox.toList(); val savedLast = hlcLast
        outbox.removeAll { it in drop } // 2. discard
        if (sel.c.any { it.lastError == "clock_ahead" }) hlcLast = (listOf(hlcAcked) + outbox.map { it.op["hlc"].asText() }).max()
        if (!clockGuardOk()) { // 3. abort: nothing changes
            outbox.clear(); outbox += savedOutbox; hlcLast = savedLast
            throw IllegalStateException("fix your clock")
        }
        val todo = sel.r.map { it.op["payload"] }.filter { r.t.getValue("review_log").getValue(it["id"].asText())["undone_hlc"] == null }
        val items = sel.e.map { wallOf(it.op["hlc"].asText()) to Reserved.CLOCK_FLOOR_MS } +
            todo.map { p ->
                val card = p["card_id"].asText()
                p["reviewed_at"].asLong() to (if (card in sel.w) Reserved.CLOCK_FLOOR_MS else r.t.getValue("card").getValue(card)["created_at"] as Long)
            }
        if (items.isNotEmpty()) { // 4-5. one shift for the whole batch
            val (delta, times) = remintTimes(items, remintOffset ?: 0, pt)
            for ((row, v) in sel.e.zip(times)) { // 6a. same ids; window-derived deck items re-keyed per 10.4
                val type = OpType.entries.first { it.wire == row.op["op_type"].asText() }
                for (q in sel.payloads.getValue(row.op["op_id"].asText())) {
                    @Suppress("UNCHECKED_CAST")
                    make(type, plain(q) as Map<String, Any?>, at = v)
                }
            }
            for ((p, t) in todo.zip(times.drop(sel.e.size))) { // 6b. reviews with new ids, in the original hlc order
                @Suppress("UNCHECKED_CAST")
                val q = (plain(p) as Map<String, Any?>).toMutableMap()
                q["id"] = newId(3); q["reviewed_at"] = t
                (q["manual_due_at"] as Long?)?.let { q["manual_due_at"] = it + delta }
                make(OpType.REVIEW_LOGGED, q)
            }
        }
        remintOffset = null // 7.
        rebuildPending = true
    }

    /** Sync cycle (SYNC.md 6.1): push, then pull; a pending rebuild runs first. */
    fun sync(server: SimServer) {
        push(server)
        if (!rebuildPending) pull(server)
        if (rebuildPending) rebuild(server)
        gc()
    }

    companion object {
        val CLOCK_CODES = setOf("clock_ahead", "clock_behind")
        val CREATES = setOf("note_type.created", "note.created", "card.created", "deck.created", "grammar.created", "fsrs_params.created")

        /** Ids of the rows whose absence makes the op `unknown_reference` (SYNC.md 10.4 step 1; `model.refs`). */
        fun refs(op: JsonNode): Set<String> {
            val t = op["op_type"].asText()
            val p = op["payload"]
            val named = mapOf(
                "note.created" to listOf("note_type_id", "grammar_point_id"), "card.created" to listOf("note_id", "preset_id"),
                "card.preset_set" to listOf("id", "preset_id"), "review.logged" to listOf("card_id"), "review.undone" to listOf("review_id"),
                "deck.item_put" to listOf("deck_id"), "deck.item_removed" to listOf("deck_id"),
                "confusable.confirmed" to listOf("note_a_id", "note_b_id"), "confusable.rejected" to listOf("note_a_id", "note_b_id"),
                "grammar.span_put" to listOf("grammar_point_id"), "grammar.span_removed" to listOf("grammar_point_id"),
            )
            val keys = named[t] ?: if (t in CREATES || p["id"] == null) emptyList() else listOf("id")
            val out = keys.mapNotNull { k -> p[k]?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotEmpty() } }.toMutableSet()
            if (t.startsWith("deck.item_") && p["item_key"].asText().startsWith("n:")) out += p["item_key"].asText().substring(2)
            return out
        }

        /** Ids in the identity of the row the op writes (10.4 "window row"; `model.row_ids`): `entity_id` parts, `n:<id>` as `<id>`. */
        fun rowIds(op: JsonNode): Set<String> {
            val parts = op["entity_id"].asText().split("|").toSet()
            return parts + parts.filter { it.startsWith("n:") }.map { it.substring(2) }
        }

        /** The entry_seq an op addresses by entry (10.4 step 1c "window-derived"; `model.window_seq`), or null. */
        fun windowSeq(op: JsonNode): Long? {
            val t = op["op_type"].asText()
            val p = op["payload"]
            if ((t == "deck.item_put" || t == "deck.item_removed") && p["item_key"].asText().startsWith("e:")) {
                return p["item_key"].asText().substring(2).toLong()
            }
            if (t == "word.status_set" || t == "word.status_cleared") return p["entry_seq"].asLong()
            return null
        }

        /** Step 1 of Re-mint (SYNC.md 10.4 1a/1b/1c, the order of E, the step-6a payloads): a port of `model.remint_select`. */
        fun remintSelect(rows: List<Row>): Selection {
            val w = mutableSetOf<String>()
            val c = mutableListOf<Row>(); val e = mutableListOf<Row>(); val rr = mutableListOf<Row>()
            val u = mutableListOf<Row>(); val d = mutableListOf<Row>()
            fun hlc(row: Row) = row.op["hlc"].asText()
            fun type(row: Row) = row.op["op_type"].asText()
            fun pid(row: Row) = row.op["payload"]["id"].asText()
            val ordered = rows.sortedBy(::hlc)
            for (row in ordered) { // 1a / 1b: one pass in ascending original hlc
                val t = type(row)
                if (row.lastError in CLOCK_CODES) {
                    c += row
                    when {
                        t == "review.logged" -> rr += row
                        t in CREATES -> { e += row; w += pid(row) }
                        rowIds(row.op).any { it in w } -> e += row
                    }
                } else if (row.lastError == "unknown_reference" && refs(row.op).any { it in w || it in rr.map(::pid) }) {
                    when {
                        t == "review.logged" -> rr += row
                        t == "review.undone" -> u += row
                        t in CREATES || rowIds(row.op).any { it in w } -> { e += row; if (t in CREATES) w += pid(row) }
                        else -> d += row
                    }
                }
            }
            // 1c: window-derived ops; the first note.created (hlc order) in E with the entry owns the seq.
            val owner = LinkedHashMap<Long, JsonNode>()
            for (row in e) {
                val p = row.op["payload"]
                if (type(row) == "note.created" && p["entry_seq"]?.isNull == false) owner.putIfAbsent(p["entry_seq"].asLong(), row.op)
            }
            val taken = (e + rr).toSet()
            for (row in ordered) {
                if (row in taken || row !in c) continue
                if (windowSeq(row.op)?.let { it in owner } == true) e += row
            }
            fun noteHlc(row: Row) = windowSeq(row.op)?.let { owner[it] }?.get("hlc")?.asText() ?: ""
            val sorted = e.sortedWith(
                compareBy<Row>({ maxOf(hlc(it), noteHlc(it)) }, { hlc(it) < noteHlc(it) }, { hlc(it) }),
            )
            // 6a payloads: e:<seq> put into a deck not in W re-keyed n:<owner> iff the owner is live; a removal
            // of a (deck, e:<seq>) whose put E re-keyed (decided in E order) is minted twice: n:<owner>, then e:<seq>.
            val deleted = sorted.filter { type(it) == "note.deleted" }.map(::pid).toSet()
            val rekeyed = mutableSetOf<Pair<String, Long>>()
            val payloads = LinkedHashMap<String, List<ObjectNode>>()
            for (row in sorted) {
                val o = row.op
                val p = o["payload"].deepCopy<ObjectNode>()
                val seq = windowSeq(o)
                val t = type(row)
                val ownerNote = seq?.let { owner[it] }?.get("payload")?.get("id")?.asText()
                if (seq != null && ownerNote != null && t.startsWith("deck.item_") && p["deck_id"].asText() !in w) {
                    val deck = p["deck_id"].asText()
                    if (t == "deck.item_put" && ownerNote !in deleted) {
                        rekeyed += deck to seq
                        p.put("item_key", "n:$ownerNote")
                    } else if (t == "deck.item_removed" && (deck to seq) in rekeyed) {
                        val first = p.deepCopy().put("item_key", "n:$ownerNote")
                        payloads[o["op_id"].asText()] = listOf(first, p)
                        continue
                    }
                }
                payloads[o["op_id"].asText()] = listOf(p)
            }
            return Selection(c, sorted, rr, u, d, w, payloads)
        }
    }
}
