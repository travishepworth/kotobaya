package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.rejected.RejectedActionResult
import kotobaya.sync.client.remint.RemintOutcome
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import java.util.TreeMap
import kotlin.random.Random

/** Named counters of one run (or of many, [plus]). */
class Stats {
    val counts = TreeMap<String, Long>()

    fun inc(key: String, n: Long = 1) {
        if (n != 0L) counts[key] = (counts[key] ?: 0L) + n
    }

    operator fun get(key: String): Long = counts[key] ?: 0L

    fun plus(o: Stats) = o.counts.forEach { (k, v) -> inc(k, v) }
}

/** What the simulation knows about one review the user made (the "zero lost reviews" oracle). */
class ReviewRec(val id: String, val device: String, val trueTime: Long) {
    /** A `review.undone` for it was minted (and not discarded). */
    var undone = false

    /** The user discarded it (Discard), or Re-mint dropped it because it was undone (SYNC.md 10.4 step 4). */
    var discarded = false

    /** Re-mint replaced it by this id (SYNC.md 10.4 step 6b). */
    var replacedBy: String? = null
}

/** Every review made on any device, and what the user did with it. */
class Ledger {
    val reviews = LinkedHashMap<String, ReviewRec>()
    private val reviewOfOp = HashMap<String, String>()
    private val undoOfOp = HashMap<String, String>()

    fun logged(op: Op, device: String, trueTime: Long) {
        val id = op.p("id")!!
        reviews[id] = ReviewRec(id, device, trueTime)
        reviewOfOp[op.opId] = id
    }

    fun undone(op: Op) {
        val rid = op.p("review_id")!!
        undoOfOp[op.opId] = rid
        reviews[rid]?.undone = true
    }

    /** A rejected row the user discarded or Re-mint deleted without minting again. Returns a violation, or null. */
    fun discarded(opId: String): String? {
        reviewOfOp[opId]?.let { rid ->
            val rec = reviews.getValue(rid)
            rec.discarded = true
            return null
        }
        undoOfOp[opId]?.let { rid -> reviews[rid]?.let { if (!it.discarded) it.undone = false } }
        return null
    }

    /** Re-mint dropped review [opId] in step 4: legal only if it was undone. */
    fun remintDropped(opId: String): String? {
        val rid = reviewOfOp[opId] ?: return null
        val rec = reviews.getValue(rid)
        val wasUndone = rec.undone
        rec.discarded = true
        return if (wasUndone) null else "Re-mint dropped review $rid, which was never undone"
    }

    fun reminted(old: String, new: String, op: Op) {
        val rec = reviews.getValue(old)
        rec.replacedBy = new
        reviews[new] = ReviewRec(new, rec.device, rec.trueTime)
        reviewOfOp[op.opId] = new
    }

    /** Reviews that must exist on every replica. */
    fun expected(): Set<String> = reviews.values.filter { !it.undone && !it.discarded && it.replacedBy == null }.mapTo(HashSet()) { it.id }

    /** Original ids replaced by Re-mint: must exist nowhere (SYNC.md 12.6). */
    fun replaced(): Set<String> = reviews.values.filter { it.replacedBy != null }.mapTo(HashSet()) { it.id }
}

/** What the user does with rejected rows (SYNC.md 10.2). */
enum class Choice { REMINT, DISCARD_ALL, RETRY_THEN_REMINT }

/**
 * The simulation state of one run: the server, the devices, true time, the review ledger, the action
 * implementations (all through the real client APIs) and the end-of-run oracle.
 */
class Sim(val server: SimServer, val world: World, val devices: List<SimDevice>, val legacy: Set<Int> = emptySet()) {
    val ledger = Ledger()
    val stats = Stats()
    val violations = ArrayList<String>()
    private val publishedVersion = HashMap<String, Long>()

    fun at(t: Long) {
        world.now = t
        server.nowMs = t
    }

    // ---- reading a device's state -------------------------------------------------------------

    private fun live(r: Row) = r.isNull("deleted_at") && r.isNull("merged_into_id")

    fun liveNotes(t: InMemoryLearningTables): List<Row> = t.all(LearningTable.NOTE).filter(::live).sortedBy { it.str("id") }

    /** Cards a review session shows: live, not a loser, of a live note. */
    fun liveCards(t: InMemoryLearningTables): List<Row> {
        val notes = liveNotes(t).mapTo(HashSet()) { it.str("id") }
        return t.all(LearningTable.CARD).filter { live(it) && it.str("note_id") in notes }.sortedBy { it.str("id") }
    }

    private fun <T> List<T>.pick(rnd: Random): T? = if (isEmpty()) null else this[rnd.nextInt(size)]

    // ---- minting ------------------------------------------------------------------------------

    /** Mints through the device's `OpMinter`; a refusal is counted (nothing minted, so nothing to lose). */
    fun mint(d: SimDevice, vararg reqs: MintRequest): List<Op>? = when (val r = d.mint(*reqs)) {
        is MintResult.Minted -> {
            for (op in r.ops) {
                stats.inc("minted.${op.opType.wire}")
                if (op.opType == OpType.REVIEW_LOGGED) ledger.logged(op, d.id, world.now)
                if (op.opType == OpType.REVIEW_UNDONE) ledger.undone(op)
            }
            r.ops
        }
        is MintResult.Refused -> {
            val why = when (val reason = r.reason) {
                is MintRefusal.ClockGuard -> "clock_guard_${reason.guard.name.lowercase()}"
                is MintRefusal.ClockFarFromServer -> "clock_far_from_server"
                MintRefusal.RebuildPending -> "rebuild_pending"
                else -> {
                    violations += "${d.name}: the minter refused a generated change: $reason"
                    "other"
                }
            }
            stats.inc("refused.$why")
            null
        }
    }

    private fun req(type: OpType, vararg pairs: Pair<String, Any?>) = MintRequest(type, obj(*pairs))

    // ---- user actions -------------------------------------------------------------------------

    fun review(d: SimDevice, rnd: Random, legacyClient: Boolean) {
        val card = liveCards(d.tables()).pick(rnd) ?: return
        val type = listOf("scheduled", "scheduled", "scheduled", "learning", "learning", "drill", "cram", "manual").pick(rnd)!!
        val id = d.ids.newOpId(d.clock.nowMs())
        val p = LinkedHashMap<String, Any?>()
        p["id"] = id
        p["card_id"] = card.str("id")
        p["reviewed_at"] = d.clock.nowMs()
        p["utc_offset_min"] = 540
        p["review_type"] = type
        if (type == "manual") {
            p["rating"] = null
            if (rnd.nextBoolean()) {
                p["manual_action"] = "set_due"
                p["manual_due_at"] = d.clock.nowMs() + rnd.nextLong(1, 10) * DAY
            } else {
                p["manual_action"] = "reset"
            }
            stats.inc("review.manual")
        } else {
            val (rating, button) = BUTTONS.pick(rnd)!!
            p["rating"] = rating
            p["button"] = button
            p["duration_ms"] = rnd.nextLong(500, 30_000)
            stats.inc("review.button.$button")
        }
        val payload = json(p) as JsonObject
        if (legacyClient) {
            when (val r = d.mint(MintRequest(OpType.REVIEW_LOGGED, payload))) {
                is MintResult.Minted -> {
                    stats.inc("minted.review.logged")
                    ledger.logged(r.ops.single(), d.id, world.now)
                }
                is MintResult.Refused -> if (r.reason is MintRefusal.ClockGuard || r.reason is MintRefusal.ClockFarFromServer) {
                    // SYNC.md 12.6: a client without the guards mints anyway (wrong reviewed_at, HLC counting on last)
                    val op = d.mintUnguarded(OpType.REVIEW_LOGGED, payload)
                    stats.inc("minted.review.logged")
                    stats.inc("legacy.unguarded_review")
                    ledger.logged(op, d.id, world.now)
                } else {
                    stats.inc("refused.legacy")
                }
            }
            return
        }
        mint(d, MintRequest(OpType.REVIEW_LOGGED, payload))
    }

    fun undo(d: SimDevice, rnd: Random) {
        val recent = d.tables().all(LearningTable.REVIEW_LOG).filter { it.isNull("undone_at") }
            .sortedByDescending { it.num("reviewed_at") }.take(4)
        val r = recent.pick(rnd) ?: return
        mint(d, req(OpType.REVIEW_UNDONE, "review_id" to r.str("id")))
    }

    /**
     * Takoboto-style add (the shape `NoteCreator` mints, SYNC.md 4): refuse a local duplicate (a live
     * note with the same `entry_seq`), else `note.created` + the default cards (+ `deck.item_put e:<seq>`)
     * in one transaction.
     */
    fun addWord(d: SimDevice, rnd: Random, entry: Long = ENTRIES.pick(rnd)!!) {
        val t = d.tables()
        if (liveNotes(t).any { it.num("entry_seq") == entry }) {
            stats.inc("add.duplicate_refused")
            return
        }
        val now = d.clock.nowMs()
        val note = d.ids.newOpId(now)
        val reqs = arrayListOf(
            req(
                OpType.NOTE_CREATED, "id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to entry,
                "fields" to mapOf("meaning" to "m${rnd.nextInt(100)}", "sentence" to "s${rnd.nextInt(100)}"),
                "tags" to listOf("mined"), "source_sentence" to "s", "origin" to "takoboto",
            ),
            req(OpType.CARD_CREATED, "id" to d.ids.newOpId(now), "note_id" to note, "kind" to "meaning"),
            req(OpType.CARD_CREATED, "id" to d.ids.newOpId(now), "note_id" to note, "kind" to "reading"),
        )
        val deck = userDecks(t).pick(rnd)
        if (deck != null && rnd.nextBoolean()) reqs += req(OpType.DECK_ITEM_PUT, "deck_id" to deck.str("id"), "item_key" to "e:$entry", "position" to rnd.nextLong(0, 100_000))
        mint(d, *reqs.toTypedArray())
    }

    fun addKanji(d: SimDevice, rnd: Random, literal: String = KANJI.pick(rnd)!!) {
        val t = d.tables()
        if (liveNotes(t).any { it.str("kanji_literal") == literal }) return
        val now = d.clock.nowMs()
        val note = d.ids.newOpId(now)
        mint(
            d,
            req(OpType.NOTE_CREATED, "id" to note, "note_type_id" to Reserved.NOTE_TYPE_KANJI, "kanji_literal" to literal, "origin" to "auto_kanji"),
            req(OpType.CARD_CREATED, "id" to d.ids.newOpId(now), "note_id" to note, "kind" to "keyword"),
        )
    }

    fun editNote(d: SimDevice, rnd: Random) {
        val n = liveNotes(d.tables()).pick(rnd) ?: return
        val set: Map<String, Any?> = when (rnd.nextInt(3)) {
            0 -> mapOf("fields" to mapOf("meaning" to "edit${rnd.nextInt(1000)}"))
            1 -> mapOf("tags" to listOf("t${rnd.nextInt(5)}"))
            else -> mapOf("mnemonic" to "mn${rnd.nextInt(1000)}")
        }
        mint(d, req(OpType.NOTE_UPDATED, "id" to n.str("id"), "set" to set))
    }

    fun deleteNote(d: SimDevice, rnd: Random) {
        val n = liveNotes(d.tables()).pick(rnd) ?: return
        mint(d, req(OpType.NOTE_DELETED, "id" to n.str("id")))
    }

    fun deleteCard(d: SimDevice, rnd: Random) {
        val c = liveCards(d.tables()).pick(rnd) ?: return
        mint(d, req(OpType.CARD_DELETED, "id" to c.str("id")))
    }

    fun cardState(d: SimDevice, rnd: Random) {
        val c = liveCards(d.tables()).pick(rnd) ?: return
        val id = c.str("id")
        when (rnd.nextInt(4)) {
            0 -> mint(d, req(OpType.CARD_SUSPENDED, "id" to id))
            1 -> mint(d, req(OpType.CARD_UNSUSPENDED, "id" to id))
            2 -> mint(d, req(OpType.CARD_BURIED, "id" to id, "until" to d.clock.nowMs() + DAY))
            else -> mint(d, req(OpType.CARD_UNBURIED, "id" to id))
        }
    }

    fun wordStatus(d: SimDevice, rnd: Random) {
        val entry = ENTRIES.pick(rnd)!!
        if (rnd.nextInt(4) == 0) {
            mint(d, req(OpType.WORD_STATUS_CLEARED, "entry_seq" to entry))
        } else {
            mint(d, req(OpType.WORD_STATUS_SET, "entry_seq" to entry, "status" to listOf("known", "never_forget", "blacklisted", "ignored").pick(rnd)))
        }
    }

    private fun userDecks(t: InMemoryLearningTables) = t.all(LearningTable.DECK).filter { live(it) && it.str("type") == "user" }.sortedBy { it.str("id") }

    fun deck(d: SimDevice, rnd: Random) {
        val t = d.tables()
        val decks = userDecks(t)
        val deck = decks.pick(rnd)
        if (deck == null || rnd.nextInt(6) == 0) {
            mint(d, req(OpType.DECK_CREATED, "id" to d.ids.newOpId(d.clock.nowMs()), "name" to "deck ${rnd.nextInt(10)}", "type" to "user"))
            return
        }
        val key = if (rnd.nextBoolean()) "e:${ENTRIES.pick(rnd)}" else liveNotes(t).pick(rnd)?.let { "n:${it.str("id")}" } ?: return
        if (rnd.nextInt(3) == 0) {
            mint(d, req(OpType.DECK_ITEM_REMOVED, "deck_id" to deck.str("id"), "item_key" to key))
        } else {
            mint(d, req(OpType.DECK_ITEM_PUT, "deck_id" to deck.str("id"), "item_key" to key, "position" to rnd.nextLong(0, 100_000)))
        }
    }

    private fun presets(t: InMemoryLearningTables) = t.all(LearningTable.FSRS_PARAMS).filter { it.isNull("deleted_at") }.sortedBy { it.str("id") }

    fun preset(d: SimDevice, rnd: Random) {
        val t = d.tables()
        when (rnd.nextInt(5)) {
            0 -> mint(d, req(OpType.FSRS_PARAMS_CREATED, "id" to d.ids.newOpId(d.clock.nowMs()), "name" to "preset ${rnd.nextInt(10)}", "desired_retention" to 0.85 + rnd.nextInt(10) / 100.0))
            1, 2 -> {
                val p = presets(t).pick(rnd) ?: return
                val set: Map<String, Any?> = if (rnd.nextBoolean()) {
                    mapOf("desired_retention" to 0.8 + rnd.nextInt(15) / 100.0)
                } else {
                    mapOf("learning_steps_s" to listOf(60, 600 * (1 + rnd.nextInt(3))))
                }
                mint(d, req(OpType.FSRS_PARAMS_UPDATED, "id" to p.str("id"), "set" to set))
            }
            else -> {
                val c = liveCards(t).pick(rnd) ?: return
                val p = presets(t).pick(rnd) ?: return
                mint(d, req(OpType.CARD_PRESET_SET, "id" to c.str("id"), "preset_id" to p.str("id")))
            }
        }
    }

    fun settings(d: SimDevice, rnd: Random) {
        mint(d, req(OpType.SETTINGS_SET, "key" to listOf("scheduler.new_per_day", "ui.theme", "review.show_timer").pick(rnd), "value" to rnd.nextInt(50)))
    }

    // ---- server-side events ---------------------------------------------------------------------

    /**
     * A server-originated, import-like add (the jpdb import's REST path): the server checks its own
     * state for the word (and its kanji) and mints the notes and a card. A phone that added the same word
     * offline meets it in the 8.1 merge when it pushes.
     */
    fun serverImport(rnd: Random) {
        val t = replicaOf(server.log())
        val entry = ENTRIES.pick(rnd)!!
        val literal = KANJI.pick(rnd)!!
        if (liveNotes(t).none { it.num("entry_seq") == entry }) {
            val note = server.emit(OpType.NOTE_CREATED, obj("id" to newServerId(rnd), "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to entry, "origin" to "jpdb_import", "fields" to mapOf("meaning" to "jpdb")))
            server.emit(OpType.CARD_CREATED, obj("id" to newServerId(rnd), "note_id" to note.p("id"), "kind" to "meaning"))
            stats.inc("server.import.vocab")
        }
        if (liveNotes(t).none { it.str("kanji_literal") == literal }) {
            val note = server.emit(OpType.NOTE_CREATED, obj("id" to newServerId(rnd), "note_type_id" to Reserved.NOTE_TYPE_KANJI, "kanji_literal" to literal, "origin" to "jpdb_import"))
            server.emit(OpType.CARD_CREATED, obj("id" to newServerId(rnd), "note_id" to note.p("id"), "kind" to "keyword"))
            stats.inc("server.import.kanji")
        }
    }

    private fun newServerId(rnd: Random): String = kotobaya.sync.client.store.UuidV7Source(rnd).newOpId(world.now)

    /** The optimizer publishes new weights for a preset the server has (`fsrs_params.published`, server-minted). */
    fun serverPublish(rnd: Random) {
        val t = replicaOf(server.log())
        val preset = presets(t).pick(rnd) ?: return
        val id = preset.str("id")!!
        val v = maxOf(publishedVersion[id] ?: 0L, preset.num("weights_version") ?: 0L) + 1
        publishedVersion[id] = v
        server.emit(
            OpType.FSRS_PARAMS_PUBLISHED,
            obj(
                "id" to id, "weights_version" to v, "fsrs_version" to "6", "weights" to WEIGHTS.map { it + rnd.nextInt(5) / 1000.0 },
                "optimized_at" to world.now, "optimizer_stats" to mapOf("review_count" to 100, "log_loss" to 0.3, "rmse_bins" to 0.03),
            ),
        )
        stats.inc("server.publish")
    }

    fun backup() {
        server.backup()
        stats.inc("server.backup")
    }

    fun restore() {
        server.restoreLatestBackup()
        stats.inc("server.restore")
    }

    // ---- syncing and the rejected-ops actions ---------------------------------------------------

    /** One sync cycle of [d], then the user's answer to any rejected rows (with [choice]). */
    fun sync(d: SimDevice, choice: Choice?, final: Boolean = false) {
        val epoch = d.state(SyncStateKeys.LOG_EPOCH)
        val r = d.sync()
        record(r)
        if (epoch != null && d.state(SyncStateKeys.LOG_EPOCH) != epoch) stats.inc("device.log_reset")
        if (choice != null) resolve(d, choice, final)
    }

    private fun record(r: CycleResult?) {
        when (r) {
            null -> stats.inc("sync.killed")
            is CycleResult.Completed -> stats.inc("sync.completed")
            is CycleResult.Failed -> stats.inc("sync.failed.${r.kind.name.lowercase()}")
            else -> stats.inc("sync.${r::class.simpleName?.lowercase()}")
        }
    }

    /**
     * The user acts on the rejected rows in the sync status (SYNC.md 10.2): clock rejections get
     * Re-mint (the default), Discard all, or Retry of the retryable ones and then Re-mint; other codes
     * are left to the auto-retry of 6.2, and in the final phase the user retries them.
     */
    fun resolve(d: SimDevice, choice: Choice, final: Boolean) {
        val svc = d.app.rejected
        val entries = svc.list()
        if (entries.isEmpty()) return
        val clock = entries.any { it.code == "clock_ahead" || it.code == "clock_behind" }
        for (e in entries) if (e.parentOpId == null) stats.inc("rejected.${e.code}")
        if (!clock) {
            if (final) {
                val retry = entries.filter { it.retryable }.map { it.row.opId }
                if (retry.isNotEmpty() && svc.retry(retry) is RejectedActionResult.Done) stats.inc("action.retry", retry.size.toLong())
            }
            return
        }
        when (choice) {
            Choice.DISCARD_ALL -> {
                val rows = entries.map { it.row }
                when (val res = svc.discardAll()) {
                    is RejectedActionResult.Done -> {
                        stats.inc("action.discard_all")
                        for (row in rows) {
                            if (row.opType == OpType.REVIEW_LOGGED.wire) stats.inc("action.discard_all.reviews")
                            ledger.discarded(row.opId)?.let { violations += it }
                        }
                        sync(d, null)
                    }
                    is RejectedActionResult.Unavailable -> stats.inc("action.discard_all.unavailable.${res.reason.lowercase()}")
                }
            }
            Choice.RETRY_THEN_REMINT -> {
                val retry = entries.filter { it.retryable }.map { it.row.opId }
                if (retry.isNotEmpty() && svc.retry(retry) is RejectedActionResult.Done) {
                    stats.inc("action.retry", retry.size.toLong())
                    sync(d, null) // answered again (rejected again while their parent is)
                }
                remint(d)
            }
            Choice.REMINT -> remint(d)
        }
    }

    private fun remint(d: SimDevice) {
        val svc = d.app.rejected
        repeat(2) {
            val preview = svc.previewRemint()
            if (!preview.available) {
                stats.inc("remint.unavailable.${preview.blocked!!.name.lowercase()}")
                return
            }
            val rows = d.rows().associateBy { it.opId }
            when (val out = svc.remint(preview)) {
                is RemintOutcome.Reminted -> {
                    stats.inc("remint.done")
                    stats.inc("remint.minted_ops", out.minted.size.toLong())
                    stats.inc("remint.reviews", out.reviewIds.size.toLong())
                    stats.inc("remint.discarded", out.discarded.size.toLong())
                    for (e in preview.discards) stats.inc("remint.discard.${e.reason.name.lowercase()}")
                    if (out.delta != 0L) stats.inc("remint.shifted")
                    val byReviewId = out.minted.filter { it.opType == OpType.REVIEW_LOGGED }.associateBy { it.p("id") }
                    for ((old, new) in out.reviewIds) ledger.reminted(old, new, byReviewId.getValue(new))
                    val dropped = preview.discards.filter { it.reason.name == "UNDONE_REVIEW" }.mapTo(HashSet()) { it.row.opId }
                    for (opId in out.discarded) {
                        val row = rows[opId]
                        if (opId in dropped) {
                            ledger.remintDropped(opId)?.let { violations += "${d.name}: $it" }
                        } else {
                            if (row?.opType == OpType.REVIEW_LOGGED.wire) violations += "${d.name}: Re-mint discarded review op $opId (${row.lastError})"
                            ledger.discarded(opId)
                        }
                    }
                    sync(d, null)
                    return
                }
                RemintOutcome.StalePreview -> stats.inc("remint.stale_preview")
                is RemintOutcome.Unavailable -> {
                    stats.inc("remint.unavailable.${out.reason.name.lowercase()}")
                    return
                }
            }
        }
    }

    // ---- the end of the week ------------------------------------------------------------------

    /**
     * Final syncs in random orders: clocks that are wrong get fixed, faults stop, everyone is online;
     * rounds of cycles (some devices only push in the first round) and the users' answers to rejected
     * rows, until every outbox is answered and every device has pulled the whole log.
     */
    fun finish(rnd: Random) {
        for (d in devices) {
            d.transport.clearFaults()
            d.transport.offline = false
            val blocked = d.state(SyncStateKeys.CLOCK_BLOCKED) != null
            if (kotlin.math.abs(d.clock.error) > 5 * MINUTE || blocked || d.rows().any { it.state == PendingState.REJECTED }) {
                d.clock.fix(rnd.nextLong(-2 * MINUTE, 2 * MINUTE))
            }
        }
        backup()
        for (round in 0 until 16) {
            for (d in devices.shuffled(rnd)) {
                if (round == 0 && rnd.nextInt(3) == 0) {
                    record(d.pushOnly())
                    stats.inc("final.push_only")
                }
                sync(d, Choice.REMINT, final = true)
            }
            world.now += MINUTE
            server.nowMs = world.now
            if (settled()) {
                stats.inc("final.rounds", round + 1L)
                // one more round in another order: nothing may change
                for (d in devices.shuffled(rnd)) sync(d, null)
                return
            }
        }
        violations += "the devices did not settle after 16 final rounds: " + devices.joinToString { d ->
            "${d.name} pending=${d.count(PendingState.PENDING)} rejected=${d.rows().filter { it.state == PendingState.REJECTED }.map { "${it.opType} ${it.lastError}" }} " +
                "rebuild=${d.state(SyncStateKeys.REBUILD_PENDING)} pull_seq=${d.state(SyncStateKeys.PULL_SEQ)}"
        }
    }

    private fun settled(): Boolean {
        val hw = server.log().lastOrNull()?.seq ?: 0L
        return devices.all { d ->
            d.rows().none { it.state != PendingState.ACKED } && d.state(SyncStateKeys.REBUILD_PENDING) == null &&
                (d.state(SyncStateKeys.PULL_SEQ)?.toLong() ?: 0L) == hw
        }
    }

    // ---- the oracle ---------------------------------------------------------------------------

    /**
     * SYNC.md 11 and the ticket's oracle: the server's log replayed in seq order by a fresh replica is
     * the reference; every device and the server's own tables equal it (card caches included); no
     * cache differs from a full replay; every review made and not undone or user-discarded is on every
     * replica; no id replaced by Re-mint is anywhere; no op is logged twice.
     */
    fun check(): List<String> {
        val out = ArrayList(violations)
        val log = server.log()
        val ids = log.map { it.opId }
        if (ids.size != ids.toSet().size) out += "an op id is logged twice"
        val ref = replicaOf(log)
        val refP = projection(ref)
        diffRows(refP, projection(fullyReplayed(ref))).takeIf { it.isNotEmpty() }?.let { out += "reference replica: a card cache differs from a full replay: $it" }
        diffRows(refP, server.projection(refP)).takeIf { it.isNotEmpty() }?.let { out += "server (${server.kind}) differs from the replayed log: $it" }
        val expected = ledger.expected()
        val replaced = ledger.replaced()
        for ((name, t) in listOf("log" to ref) + devices.map { it.name to it.tables() }) {
            val p = projection(t)
            if (name != "log") {
                diffRows(refP, p).takeIf { it.isNotEmpty() }?.let { out += "device $name differs from the server's log: $it" }
                diffRows(p, projection(fullyReplayed(t))).takeIf { it.isNotEmpty() }?.let { out += "device $name: a card cache differs from a full replay: $it" }
            }
            val present = t.all(LearningTable.REVIEW_LOG).mapNotNullTo(HashSet()) { it.str("id") }
            val lost = expected - present
            if (lost.isNotEmpty()) out += "LOST REVIEWS on $name: ${lost.size} (e.g. ${lost.take(3).map { describe(it) }})"
            val ghosts = replaced intersect present
            if (ghosts.isNotEmpty()) out += "$name still holds ${ghosts.size} review ids replaced by Re-mint"
        }
        return out
    }

    private fun describe(id: String): String {
        val r = ledger.reviews.getValue(id)
        return "$id by ${devices.firstOrNull { it.id == r.device }?.name} at +${(r.trueTime - WEEK_START) / MINUTE}min"
    }

    /**
     * T203 follow-up: replay orders by `reviewed_at`, so a skewed clock can put a review answered later
     * (true time) before an earlier one. Counts adjacent pairs in every replay set of the reference
     * replica whose true times are out of order.
     */
    fun skewAnomalies(): Long {
        val ref = replicaOf(server.log())
        val cache = DeviceCardCache()
        var n = 0L
        for (card in ref.all(LearningTable.CARD).filter(::isMaintained)) {
            val times = cache.replaySet(ref, card.str("id")!!).mapNotNull { ledger.reviews[it.id]?.trueTime }
            for (i in 1 until times.size) if (times[i] < times[i - 1]) n++
        }
        return n
    }

    companion object {
        val ENTRIES: List<Long> = (1_358_280L..1_358_291L).toList()
        val KANJI: List<String> = listOf("食", "飲", "見", "行", "来")
        val BUTTONS: List<Pair<Int, String>> = listOf(
            1 to "again", 1 to "nothing", 1 to "something", 2 to "hard", 3 to "good", 3 to "okay", 4 to "easy",
        )
        val WEIGHTS: List<Double> = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
        )
    }
}
