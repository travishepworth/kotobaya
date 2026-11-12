package kotobaya.sync.fuzz

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.server.json
import kotobaya.sync.merge.MergeScenarioTest
import kotobaya.sync.restore.StoryServer
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Convergence fuzz (SYNC.md 11, 13): random op streams from three simulated devices with skewed
 * clocks, synced at random moments against the real server (Postgres, merge policy, core card
 * cache), must end with the server and every device replica holding the same projection — card
 * caches included (the server folds and replays op by op; the replicas only ever replay in full).
 * Then the server's log, re-applied by fresh replicas in random per-device interleavings with
 * re-deliveries (11: "any interleaving converges"), gives the same state again.
 *
 * The generator concentrates on what can diverge: a small pool of entry ids (so devices keep adding
 * the same word offline: note and card merges, two-level merges, re-matches), deletes racing
 * duplicates, register edits addressed to losers, reviews (rated, drill, manual) arriving out of
 * order and undone on several devices.
 *
 * A second part pushes the device streams of every scenario file (in the note/card/review subset) to
 * a fresh server in random arrival orders: the server's verdicts may differ by arrival order (8.4),
 * but every op is accepted and every replica of the resulting log converges with the server.
 *
 * Fixed seeds run in `make check`; set `KOTOBAYA_FUZZ_RUNS=<n>` for n more seeds with longer runs.
 */
class ConvergenceFuzzTest {
    private val start = 0x0193a1b2c000L
    private val devices = listOf(
        "01931f3e-0000-7000-8000-0000000000a1" to 0L, // correct clock
        "01931f3e-0000-7000-8000-0000000000b2" to 3 * 3_600_000L, // 3 h ahead (within the 24 h tolerance)
        "01931f3e-0000-7000-8000-0000000000c3" to -2 * 3_600_000L, // 2 h behind
    )
    private val kinds = listOf("meaning", "reading", "listening")

    @Test
    fun fixedSeedsConverge() {
        for (seed in SEEDS) run(seed, steps = 160)
        // The generator really exercises the merge paths (else the test proves little).
        for (t in listOf("note.merged", "card.merged", "card.created@server", "review.undone")) {
            assertTrue((stats[t] ?: 0) > 0, "no $t in any seed: $stats")
        }
    }

    private val stats = mutableMapOf<String, Int>()

    @Test
    fun optInLongRun() {
        val runs = System.getenv("KOTOBAYA_FUZZ_RUNS")?.toIntOrNull() ?: return
        for (k in 0 until runs) run(10_000L + k, steps = 600)
        for (k in 0 until runs) run(20_000L + k, steps = 600, remint = true)
    }

    /**
     * Re-mint (SYNC.md 10.4, C-8) under the fuzz: decks, deck items (`e:<seq>` and `n:<note>`), word statuses
     * and presets join the stream, and now and then one device's clock jumps 10 days ahead for a while (a
     * window): everything it mints is rejected (`clock_ahead`, then `unknown_reference` for the chains). When
     * the window ends the clock is fixed, the device syncs, runs [SimDevice.remint] (step 1a/1b/1c, `D`, the
     * `n:` re-key with the live-owner condition, the double removal, steps 5/6) and syncs again; every replica
     * must still converge with the server and nothing may stay rejected.
     */
    @Test
    fun remintWindowsConverge() {
        for (seed in SEEDS) run(100L + seed, steps = 250, remint = true)
        println("re-mint fuzz coverage: $remintStats")
        for (k in REMINT_CASES) assertTrue((remintStats[k] ?: 0) > 0, "re-mint case $k never reached: $remintStats")
    }

    private val remintStats = sortedMapOf<String, Int>()

    private fun count(k: String, n: Int = 1) {
        if (n > 0) remintStats[k] = (remintStats[k] ?: 0) + n
    }

    private class Gen(val d: SimDevice, var skew: Long)

    private fun run(seed: Long, steps: Int, remint: Boolean = false) = StoryServer(start).use { s ->
        val rng = Random(seed)
        val gens = devices.map { (id, skew) -> Gen(SimDevice(id), skew) }
        var real = start
        fun clocks() {
            s.pt = real
            gens.forEach { it.d.pt = real + it.skew }
        }
        var window: Gen? = null
        var windowLeft = 0
        fun endWindow() {
            val g = window ?: return
            window = null
            g.skew = 0 // the user fixed the clock
            clocks()
            g.d.sync(s.sim) // every op minted in the window is answered
            g.d.remint()
            g.d.lastSelection?.let(::record)
            g.d.sync(s.sim)
        }
        clocks()
        repeat(steps) {
            real += rng.nextLong(1, 3_600_000)
            clocks()
            if (remint && window == null && rng.nextInt(100) < 6) {
                window = gens[rng.nextInt(gens.size)].also { it.skew = 10 * Reserved.DAY_MS }
                windowLeft = rng.nextInt(6, 30)
                count("windows")
                clocks()
            }
            val g = window?.takeIf { rng.nextBoolean() } ?: gens[rng.nextInt(gens.size)]
            try {
                act(g.d, rng, s, rich = remint)
                if (window != null && --windowLeft <= 0) endWindow()
            } catch (e: Exception) {
                throw AssertionError("seed $seed step $it (${g.d.id.takeLast(2)}): ${e.message}", e)
            }
        }
        try {
            endWindow()
        } catch (e: Exception) {
            throw AssertionError("seed $seed final window: ${e.message}", e)
        }
        repeat(3) {
            real += 1000
            clocks()
            gens.forEach { it.d.sync(s.sim) }
        }
        val label = "seed $seed"
        for (g in gens) {
            val bad = g.d.outbox.filter { it.state != "acked" }
            assertTrue(bad.isEmpty(), "$label: device ${g.d.id.takeLast(2)} has unacked ops: ${bad.map { "${it.op["op_type"]} ${it.lastError}" }}")
        }
        val sp = ServerProjection.of(s.db)
        for (g in gens) {
            assertEquals(emptyList(), ServerProjection.diff(sp, g.d.r.projection()), "$label: device ${g.d.id.takeLast(2)} vs server")
        }
        val log = serverLog(s)
        for (op in log) {
            val t = op["op_type"].asText() + if (op["op_type"].asText() == "card.created" && op["device_id"].asText() == SERVER) "@server" else ""
            stats[t] = (stats[t] ?: 0) + 1
        }
        assertInterleavingsConverge(label, log, sp, Random(seed * 31 + 7))
        assertLiveUniqueness(label, sp)
    }

    /** Which 10.4 rules one re-mint exercised. */
    private fun record(sel: SimDevice.Selection) {
        count("E", sel.e.size); count("R", sel.r.size); count("U", sel.u.size); count("D", sel.d.size)
        for (row in sel.e) {
            val o = row.op
            if (row.lastError !in SimDevice.CLOCK_CODES || SimDevice.windowSeq(o) == null) continue
            count("window-derived")
            val t = o["op_type"].asText()
            if (t.startsWith("word.status")) count("word-status")
            if (!t.startsWith("deck.item_") || o["payload"]["deck_id"].asText() in sel.w) continue
            val minted = sel.payloads.getValue(o["op_id"].asText())
            when {
                minted.size == 2 -> count("double-removal")
                minted[0]["item_key"].asText() != o["payload"]["item_key"].asText() -> count("rekeyed")
                t == "deck.item_put" -> count("kept-e-put")
                else -> count("kept-e-removal")
            }
        }
    }

    /**
     * Decks, deck items, word statuses and presets (the rows Re-mint's E rules touch; C-8). Entry seqs share the
     * notes' pool (1..5), so `e:<seq>` items and statuses are window-derived whenever a window note has the entry.
     */
    private fun actRich(d: SimDevice, rng: Random, notes: List<Map<String, Any?>>, cards: List<Map<String, Any?>>) {
        val decks = d.r.t.getValue("deck").values.filter { it["deleted_at"] == null }
        fun key(): String = notes.randomOrNull(rng)?.takeIf { rng.nextInt(3) == 0 }?.let { "n:${it["id"]}" } ?: "e:${rng.nextLong(1, 6)}"
        when (rng.nextInt(100)) {
            in 0 until 8 -> d.make(OpType.DECK_CREATED, mapOf("id" to d.newId(4), "name" to "deck ${rng.nextInt(9)}", "type" to "user"))
            in 8 until 20 -> {
                // Mining into a deck: a new word's note and card plus its e:<seq> item, the item put first
                // sometimes (a pending entry materialised later; 10.4 "Order of E").
                val deck = decks.randomOrNull(rng) ?: return
                val seq = rng.nextLong(1, 6)
                if (notes.any { it["deleted_at"] == null && it["entry_seq"] == seq }) return
                val first = rng.nextBoolean()
                fun put() = d.make(OpType.DECK_ITEM_PUT, mapOf("deck_id" to deck["id"], "item_key" to "e:$seq", "position" to rng.nextLong(0, 4096)))
                if (first) put()
                val id = d.newId(1)
                d.make(OpType.NOTE_CREATED, mapOf("id" to id, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to seq))
                d.make(OpType.CARD_CREATED, mapOf("id" to d.newId(2), "note_id" to id, "kind" to "meaning"))
                if (!first) put()
                when (rng.nextInt(4)) { // the user changes their mind (10.4: double removal; owner deleted → keeps e:)
                    0 -> d.make(OpType.DECK_ITEM_REMOVED, mapOf("deck_id" to deck["id"], "item_key" to "e:$seq"))
                    1 -> d.make(OpType.NOTE_DELETED, mapOf("id" to id))
                    else -> Unit
                }
            }
            in 20 until 45 -> {
                val deck = decks.randomOrNull(rng) ?: return
                d.make(OpType.DECK_ITEM_PUT, mapOf("deck_id" to deck["id"], "item_key" to key(), "position" to rng.nextLong(0, 4096)))
            }
            in 45 until 65 -> {
                val deck = decks.randomOrNull(rng) ?: return
                // Mostly a key the deck holds (the removal of something put), else any key.
                val held = d.r.t.getValue("deck_item").values.filter { it["deck_id"] == deck["id"] && it["deleted_at"] == null }
                val k = held.randomOrNull(rng)?.takeIf { rng.nextInt(4) != 0 }?.get("item_key") as String? ?: key()
                d.make(OpType.DECK_ITEM_REMOVED, mapOf("deck_id" to deck["id"], "item_key" to k))
            }
            in 65 until 85 -> {
                val seq = rng.nextLong(1, 6)
                if (rng.nextInt(4) == 0) {
                    d.make(OpType.WORD_STATUS_CLEARED, mapOf("entry_seq" to seq))
                } else {
                    d.make(OpType.WORD_STATUS_SET, mapOf("entry_seq" to seq, "status" to listOf("known", "ignored", "never_forget", "blacklisted").random(rng)))
                }
            }
            in 85 until 92 -> {
                // A new preset, often given to an existing card right away (in a window: list D of 10.4).
                val id = d.newId(6)
                d.make(OpType.FSRS_PARAMS_CREATED, mapOf("id" to id, "name" to "preset ${rng.nextInt(9)}"))
                val card = cards.randomOrNull(rng)?.takeIf { rng.nextBoolean() } ?: return
                d.make(OpType.CARD_PRESET_SET, mapOf("id" to card["id"], "preset_id" to id))
            }
            else -> {
                val card = cards.randomOrNull(rng) ?: return
                val preset = d.r.t.getValue("fsrs_params").values.filter { it["deleted_at"] == null }.randomOrNull(rng) ?: return
                d.make(OpType.CARD_PRESET_SET, mapOf("id" to card["id"], "preset_id" to preset["id"]))
            }
        }
    }

    /** One random action of [d]: a sync, or minting one change the way a client would (SYNC.md 4 duties). */
    @Suppress("UNCHECKED_CAST")
    private fun act(d: SimDevice, rng: Random, s: StoryServer, rich: Boolean = false) {
        val notes = d.r.t.getValue("note").values.filter { it["merged_into_id"] == null }
        val liveNotes = notes.filter { it["deleted_at"] == null }
        val cards = d.r.t.getValue("card").values.filter { it["merged_into_id"] == null }
        val liveCards = cards.filter { it["deleted_at"] == null }
        val reviews = d.r.t.getValue("review_log").values.toList()
        fun entryFree(seq: Long) = liveNotes.none { it["entry_seq"] == seq }
        if (rich && rng.nextInt(100) < 35) return actRich(d, rng, notes, cards)
        when (rng.nextInt(100)) {
            in 0 until 18 -> d.sync(s.sim)
            in 18 until 32 -> {
                val seq = if (rng.nextInt(5) == 0) null else rng.nextLong(1, 6)
                if (seq != null && !entryFree(seq)) return // the client uses the existing note instead
                val id = d.newId(1)
                d.make(
                    OpType.NOTE_CREATED,
                    buildMap {
                        put("id", id); put("note_type_id", Reserved.NOTE_TYPE_VOCAB); seq?.let { put("entry_seq", it) }
                        if (rng.nextBoolean()) put("fields", mapOf("meaning" to "m${rng.nextInt(9)}", "sentence" to if (rng.nextBoolean()) "" else "s${rng.nextInt(9)}"))
                        if (rng.nextInt(3) == 0) put("tags", listOf("t${rng.nextInt(3)}"))
                    },
                )
                kinds.shuffled(rng).take(rng.nextInt(1, 3)).forEach { k -> d.make(OpType.CARD_CREATED, mapOf("id" to d.newId(2), "note_id" to id, "kind" to k)) }
            }
            in 32 until 38 -> {
                val n = liveNotes.randomOrNull(rng) ?: return
                val have = liveCards.filter { it["note_id"] == n["id"] }.map { it["kind"] as String }
                val k = (kinds - have.toSet()).randomOrNull(rng) ?: return
                d.make(OpType.CARD_CREATED, mapOf("id" to d.newId(2), "note_id" to n["id"], "kind" to k, "flags" to rng.nextInt(0, 2)))
            }
            in 38 until 52 -> {
                val n = notes.randomOrNull(rng) ?: return
                val set: Map<String, Any?> = when (rng.nextInt(5)) {
                    0 -> mapOf("tags" to listOf("t${rng.nextInt(3)}"))
                    1 -> mapOf("mnemonic" to "mn${rng.nextInt(9)}")
                    2 -> mapOf("fields" to mapOf("reading" to if (rng.nextInt(4) == 0) "" else "r${rng.nextInt(9)}"))
                    3 -> mapOf("fields" to mapOf("meaning" to "m${rng.nextInt(9)}", "sentence" to "s${rng.nextInt(9)}"))
                    else -> {
                        val seq = if (rng.nextInt(5) == 0) null else rng.nextLong(1, 6) // null: un-match
                        if ((seq != null && !entryFree(seq)) || n["deleted_at"] != null) return
                        mapOf("entry_seq" to seq) // re-match: a server merge if another device holds it
                    }
                }
                d.make(OpType.NOTE_UPDATED, mapOf("id" to n["id"], "set" to set))
            }
            in 52 until 56 -> d.make(OpType.NOTE_DELETED, mapOf("id" to (liveNotes.randomOrNull(rng) ?: return)["id"]))
            in 56 until 66 -> {
                val c = cards.randomOrNull(rng) ?: return
                when (rng.nextInt(4)) {
                    0 -> d.make(OpType.CARD_SUSPENDED, mapOf("id" to c["id"]))
                    1 -> d.make(OpType.CARD_UNSUSPENDED, mapOf("id" to c["id"]))
                    2 -> d.make(OpType.CARD_FLAG_SET, mapOf("id" to c["id"], "flags" to rng.nextInt(0, 8)))
                    else -> d.make(OpType.CARD_BURIED, mapOf("id" to c["id"], "until" to d.pt + Reserved.DAY_MS))
                }
            }
            in 66 until 68 -> d.make(OpType.CARD_DELETED, mapOf("id" to (liveCards.randomOrNull(rng) ?: return)["id"]))
            in 68 until 93 -> {
                val c = liveCards.randomOrNull(rng) ?: return
                // Answer times may be a little before "now" (an offline session flushed late).
                val at = d.pt - rng.nextLong(0, 6 * 3_600_000L)
                val payload: Map<String, Any?> = when (rng.nextInt(10)) {
                    0 -> mapOf("review_type" to "drill", "rating" to rng.nextInt(1, 5))
                    1 -> mapOf("review_type" to "manual", "rating" to null, "manual_action" to "set_due", "manual_due_at" to at + 3 * Reserved.DAY_MS)
                    2 -> mapOf("review_type" to "manual", "rating" to null, "manual_action" to "reset")
                    else -> mapOf("review_type" to if (rng.nextBoolean()) "scheduled" else "learning", "rating" to rng.nextInt(1, 5))
                }
                d.make(OpType.REVIEW_LOGGED, mapOf("id" to d.newId(3), "card_id" to c["id"], "reviewed_at" to at, "utc_offset_min" to 540) + payload)
            }
            else -> {
                val r = reviews.filter { it["undone_hlc"] == null || rng.nextInt(4) == 0 }.randomOrNull(rng) ?: return
                d.make(OpType.REVIEW_UNDONE, mapOf("review_id" to r["id"]))
            }
        }
    }

    private fun serverLog(s: StoryServer): List<JsonNode> = json.readTree(s.sim.pull(0, 1000).toString()).let { first ->
        val out = first["ops"].toMutableList()
        var page = first
        while (page["has_more"].asBoolean()) {
            page = s.sim.pull(page["next_since"].asLong(), 1000)
            out.addAll(page["ops"])
        }
        out
    }

    /** SYNC.md 11: fresh replicas applying the log in random per-device interleavings (with re-deliveries) converge. */
    private fun assertInterleavingsConverge(label: String, log: List<JsonNode>, expected: Map<String, Map<String, Any?>>, rng: Random) {
        val inOrder = RefReplica().also { r -> log.forEach(r::apply) }
        assertEquals(emptyList(), ServerProjection.diff(expected, inOrder.projection()), "$label: seq-order replica vs server")
        repeat(3) { k ->
            val got = interleaved(log, rng).projection()
            assertEquals(emptyList(), ServerProjection.diff(expected, got), "$label: interleaving $k vs server")
        }
    }

    /** At most one live note per entry and one live card per (note, kind): the server's invariant after every merge (8). */
    private fun assertLiveUniqueness(label: String, sp: Map<String, Map<String, Any?>>) {
        val liveNotes = sp.getValue("note").values.map { it as Map<*, *> }.filter { it["deleted_at"] == null && it["entry_seq"] != null }
        assertEquals(liveNotes.size, liveNotes.map { it["entry_seq"] }.toSet().size, "$label: two live notes for one entry")
        val liveCards = sp.getValue("card").values.map { it as Map<*, *> }.filter { it["deleted_at"] == null }
        assertEquals(liveCards.size, liveCards.map { it["note_id"] to it["kind"] }.toSet().size, "$label: two live cards for one (note, kind)")
    }

    @Test
    fun scenarioStreamsInRandomArrivalOrdersConverge() {
        val files = File(SyncPaths.contracts, "examples/scenarios").listFiles()!!.sortedBy { it.name }
        var ran = 0
        for (f in files) {
            val sc = json.readTree(f)
            val log = sc["log"].toList()
            if (log.any { it["op_type"].asText() !in MergeScenarioTest.REF_TYPES }) continue
            if (log.any { it["device_id"].asText() == SERVER && it["caused_by_op_id"].isNull }) continue
            for (seed in 1L..2L) {
                pushInRandomOrder("${sc["id"].asText()} seed $seed", log.filter { it["device_id"].asText() != SERVER }, Random(seed))
                ran++
            }
        }
        assertEquals(13 * 2, ran, "every scenario of the note/card/review subset ran")
    }

    private fun pushInRandomOrder(label: String, deviceOps: List<JsonNode>, rng: Random) = StoryServer(start).use { s ->
        val streams = deviceOps.groupBy { it["device_id"].asText() }.mapValues { ArrayDeque(it.value) }
        var guard = 0
        while (streams.values.any { it.isNotEmpty() }) {
            check(guard++ < 100_000) { "$label: no progress" }
            val (dev, q) = streams.entries.filter { it.value.isNotEmpty() }.random(rng)
            val op = (q.first().deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove("seq"); remove("caused_by_op_id") }
            s.pt = wallOf(op["hlc"].asText())
            val res = s.sim.push(dev, listOf(op))["results"][0]
            when (res["status"].asText()) {
                "accepted", "duplicate" -> q.removeFirst()
                else -> if (res["error"]["code"].asText() != "unknown_reference") fail("$label: ${op["op_type"]} rejected: ${res["error"]}")
            }
        }
        val sp = ServerProjection.of(s.db)
        assertInterleavingsConverge(label, serverLog(s), sp, rng)
        assertLiveUniqueness(label, sp)
    }

    companion object {
        val SEEDS = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)

        /** The SYNC.md 10.4 cases [remintWindowsConverge] must reach (counted by `record`). */
        val REMINT_CASES = listOf(
            "windows", "E", "R", "U", "D", "window-derived", "rekeyed", "double-removal", "kept-e-put", "kept-e-removal", "word-status",
        )
        const val SERVER = "00000000-0000-7000-8000-000000000001"

        /** A random order that keeps each device's ops in log order, applies an op only once the rows it references exist, and re-delivers some. */
        fun interleaved(log: List<JsonNode>, rng: Random): RefReplica {
            val streams = log.groupBy { it["device_id"].asText() }.mapValues { ArrayDeque(it.value) }
            val r = RefReplica()
            val done = mutableListOf<JsonNode>()
            var guard = 0
            while (streams.values.any { it.isNotEmpty() }) {
                check(guard++ < 1_000_000) { "interleaving makes no progress" }
                val q = streams.values.filter { it.isNotEmpty() }.random(rng)
                try {
                    r.apply(q.first()) // throws before changing anything when a referenced row is missing
                } catch (e: MissingRow) {
                    continue
                }
                done.add(q.removeFirst())
                if (rng.nextInt(10) < 3) r.apply(done.random(rng))
            }
            return r
        }
    }
}

/** Where `contracts/sync` is. */
object SyncPaths {
    val contracts: File by lazy {
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "contracts/sync") }.first { it.isDirectory }
    }
}
