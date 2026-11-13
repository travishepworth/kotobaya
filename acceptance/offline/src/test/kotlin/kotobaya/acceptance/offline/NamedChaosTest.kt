package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.remint.DiscardReason
import kotobaya.sync.client.store.PendingState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The named deterministic cases of T313 (the worked examples cover edit-vs-delete, 12.2; the
 * clock-ahead week with Re-mint, 12.7; the reset-clock week, 12.6; the restore, 12.5):
 * the same word added offline on two phones racing a server import, a guarded clock-behind week,
 * a server restore mid-week in a full offline week, kills mid-push and at every commit of a sync
 * (including a log reset and rebuild), and the Re-mint window rows the follow-ups name.
 */
class NamedChaosTest {
    @Test
    fun sameWordAddedOfflineOnTwoDevices_andAnImportRacesAPhoneKanjiAdd() = Story().use { s ->
        val rnd = Random(5)
        val entry = 1_358_280L
        s.syncOk(s.a)
        s.syncOk(s.b)
        s.a.transport.offline = true
        s.b.transport.offline = true
        s.tick(HOUR)
        s.sim.addWord(s.a, rnd, entry) // Takoboto-style add on both phones, offline
        s.sim.addKanji(s.a, rnd, "食")
        val aNotes = s.sim.liveNotes(s.a.tables()).mapNotNull { it.str("id") }
        assertEquals(2, aNotes.size)
        s.tick(MINUTE)
        s.sim.addWord(s.b, rnd, entry)
        s.sim.addWord(s.b, rnd, entry) // a second share of the same word on B: refused locally (SYNC.md 4)
        assertEquals(1, s.sim.stats["add.duplicate_refused"])
        // the jpdb import (server-minted, after checking the server's state) adds 食 while A is offline
        val imported = s.server.emit(OpType.NOTE_CREATED, obj("id" to s.a.ids.newOpId(s.world.now), "note_type_id" to Reserved.NOTE_TYPE_KANJI, "kanji_literal" to "食", "origin" to "jpdb_import"))
        s.server.emit(OpType.CARD_CREATED, obj("id" to s.a.ids.newOpId(s.world.now), "note_id" to imported.p("id"), "kind" to "keyword"))
        for (k in 0 until 3) {
            s.tick(10 * MINUTE)
            for (d in s.devices) s.sim.liveCards(d.tables()).forEach { s.review(d, it.str("id")!!, 1 + k) }
        }
        s.b.transport.offline = false
        s.syncOk(s.b)
        s.a.transport.offline = false
        s.syncOk(s.a)
        s.settleAndCheck("same word on two phones + import race")
        val ref = replicaOf(s.server.log())
        val liveNotes = s.sim.liveNotes(ref)
        assertEquals(1, liveNotes.count { it.num("entry_seq") == entry }, "one live note per word")
        assertEquals(1, liveNotes.count { it.str("kanji_literal") == "食" }, "one live kanji note")
        assertEquals(imported.p("id"), liveNotes.single { it.str("kanji_literal") == "食" }.str("id"), "the import held the kanji first: survivor")
        assertTrue(aNotes.all { id -> !ref.get(LearningTable.NOTE, listOf(JsonString(id)))!!.isNull("merged_into_id") }, "A's offline notes became merge losers")
        // every review of every phone is in the survivors' replay sets (zero lost is in settleAndCheck)
        val sets = s.sim.liveCards(ref).flatMap { s.replaySet(s.a, it.str("id")!!) }.toSet()
        assertEquals(s.sim.ledger.expected(), sets, "every review feeds a survivor's schedule")
    }

    /** The oracle itself: a review silently dropped from one replica is reported as lost and as a divergence. */
    @Test
    fun oracleCatchesALostReview() = Story().use { s ->
        val (_, cards) = s.word(s.a, 1_358_280L)
        s.tick(MINUTE)
        val r = s.review(s.a, cards.getValue("meaning"))
        s.syncOk(s.a)
        s.syncOk(s.b)
        assertEquals(emptyList(), s.sim.check())
        s.b.store.transaction { it.learning.delete(LearningTable.REVIEW_LOG, listOf(JsonString(r.p("id")!!))) }
        val v = s.sim.check()
        assertTrue(v.any { it.startsWith("LOST REVIEWS on dev1") }, "$v")
        assertTrue(v.any { it.startsWith("device dev1 differs") }, "$v")
    }

    @Test
    fun clockBehindWeek_guardedClientMintsNothingAndLosesNothing() = Story().use { s ->
        val (_, cards) = s.word(s.a, 1_358_280L)
        val c = cards.getValue("meaning")
        s.syncOk(s.a)
        s.syncOk(s.b)
        s.a.clock.offset = -3 * DAY // the phone's date goes back three days (G2)
        val start = s.world.now
        val rnd = Random(9)
        for (day in 0 until 6) {
            for (k in 0 until 5) {
                s.at(start + day * DAY + 8 * HOUR + k * MINUTE)
                s.sim.review(s.a, rnd, legacyClient = false)
                s.sim.addWord(s.a, rnd, 1_400_000L + k)
            }
            s.at(start + day * DAY + 19 * HOUR)
            s.review(s.b, c)
            s.syncOk(s.b)
            s.sync(s.a) // syncing still works; minting stays blocked
        }
        assertEquals(60, s.sim.stats["refused.clock_guard_g2_behind_last"] + s.sim.stats["refused.clock_far_from_server"], "every review and add was refused")
        assertEquals(0, s.a.rows().count { it.state != PendingState.ACKED }, "nothing was minted, so nothing can be lost")
        s.a.clock.fix()
        s.sync(s.a)
        s.tick(MINUTE)
        s.review(s.a, c) // the block lifts once the clock is right
        s.settleAndCheck("clock-behind week")
    }

    @Test
    fun serverRestoreMidWeek() {
        val steps = ArrayList<Step>()
        var r = 1L
        fun add(day: Int, minute: Long, dev: Int, kind: Kind) {
            steps += Step(WEEK_START + day * DAY + minute * MINUTE, dev, kind, r++)
        }
        for (day in 0 until 7) {
            add(day, 3 * 60, -1, Kind.BACKUP)
            for (dev in 0..1) {
                for (k in 0 until 2) add(day, 8 * 60L + k + dev * 10, dev, Kind.ADD_WORD)
                for (k in 0 until 6) add(day, 8 * 60L + 2 + k + dev * 10, dev, Kind.REVIEW)
                add(day, 9 * 60L + dev, dev, Kind.SYNC)
                for (k in 0 until 6) add(day, 20 * 60L + k + dev * 10, dev, Kind.REVIEW)
                add(day, 20 * 60L + 7 + dev * 10, dev, Kind.UNDO)
                add(day, 21 * 60L + dev, dev, Kind.SYNC)
            }
        }
        add(3, 10 * 60, -1, Kind.SERVER_IMPORT) // lost by the restore (6.2 step 4), legitimately
        add(3, 12 * 60, -1, Kind.RESTORE) // the morning's ops since the 03:00 backup are lost from the server
        steps.sortBy { it.at }
        val plan = Plan(4242, listOf(DeviceSetup(0, 0, false, false), DeviceSetup(3 * MINUTE, 20_000, false, false)), 7, steps)
        val result = Chaos.execute(plan)
        assertEquals(emptyList(), result.violations, "restore mid-week")
        assertEquals(1, result.stats["server.restore"])
        assertTrue(result.stats["device.log_reset"] >= 2, "both phones reset their log: ${result.stats.counts}")
        assertTrue(result.stats["minted.review.logged"] > 100)
    }

    @Test
    fun killedWhileThePushResponseIsInFlight() = Story().use { s ->
        val (_, cards) = s.word(s.a, 1_358_280L)
        repeat(10) { s.tick(MINUTE); s.review(s.a, cards.getValue("meaning")) }
        s.a.transport.killAfterNextPush = true
        assertNull(s.a.sync(), "the process died after the server applied the push")
        assertEquals(12, s.server.log().size, "the server committed every op")
        assertEquals(12, s.a.count(PendingState.PENDING), "the device never recorded the response: rows stay pending")
        assertEquals(2, s.a.boots)
        s.syncOk(s.a) // re-pushed: duplicate, acked with the original seq
        assertEquals(12, s.server.log().size, "nothing logged twice")
        assertEquals(0, s.a.count(PendingState.PENDING))
        s.settleAndCheck("kill mid-push")
    }

    /** A kill at the k-th commit of a sync that pushes, log-resets after a restore, rebuilds and pulls small pages. */
    @Test
    fun killedAtEveryCommitOfASyncWithALogResetAndRebuild() {
        var kills = 0
        for (k in 1..14) {
            Story(seed = k).use { s ->
                val (n, cards) = s.word(s.a, 1_358_280L)
                s.syncOk(s.a)
                s.syncOk(s.b)
                s.sim.backup()
                repeat(4) { s.tick(MINUTE); s.review(s.a, cards.getValue("meaning")); s.review(s.b, cards.getValue("meaning")) }
                s.syncOk(s.a)
                s.syncOk(s.b)
                s.sim.restore()
                s.tick(MINUTE)
                s.mint(s.b, OpType.NOTE_UPDATED, "id" to n, "set" to mapOf("tags" to listOf("k$k")))
                s.review(s.a, cards.getValue("meaning"))
                s.a.crash.killAtTx = k
                val r = s.a.sync()
                if (r == null) kills++
                s.settleAndCheck("kill at commit $k")
            }
        }
        assertTrue(kills >= 10, "the sync really was killed at most commits: $kills of 14")
    }

    /**
     * Follow-ups T202c / Phase 3: Re-mint of the window rows that were untested — `deck.item_put e:<seq>`
     * of a window word (re-keyed `n:<note>`), `word.status_set` of a window word (re-minted),
     * `card.preset_set` of a window card (re-minted) and of an existing card to a window preset (`D`:
     * discarded and listed) — with a preset update and a server publish in the same week.
     */
    @Test
    fun clockAheadWindowRowsAreRemintedOrListed() = Story().use { s ->
        val deck = s.mint(s.a, OpType.DECK_CREATED, "id" to s.a.ids.newOpId(s.world.now), "name" to "mined", "type" to "user").p("id")!!
        val preset = s.mint(s.a, OpType.FSRS_PARAMS_CREATED, "id" to s.a.ids.newOpId(s.world.now), "name" to "slow", "desired_retention" to 0.85).p("id")!!
        val existing = s.word(s.a, 1_300_000L).second.getValue("meaning")
        s.syncOk(s.a)
        s.syncOk(s.b)
        s.server.emit(OpType.FSRS_PARAMS_PUBLISHED, obj("id" to preset, "weights_version" to 1, "fsrs_version" to "6", "weights" to Sim.WEIGHTS, "optimized_at" to s.world.now, "optimizer_stats" to mapOf("review_count" to 10, "log_loss" to 0.3, "rmse_bins" to 0.03)))
        s.mint(s.b, OpType.FSRS_PARAMS_UPDATED, "id" to preset, "set" to mapOf("desired_retention" to 0.88))
        s.syncOk(s.b)
        s.a.clock.offset = 5 * DAY
        s.tick(HOUR)
        val entry = 1_358_281L
        val (note, cards) = s.word(s.a, entry, "meaning", "reading")
        s.mint(s.a, OpType.DECK_ITEM_PUT, "deck_id" to deck, "item_key" to "e:$entry", "position" to 2048)
        s.mint(s.a, OpType.WORD_STATUS_SET, "entry_seq" to entry, "status" to "known")
        s.mint(s.a, OpType.CARD_PRESET_SET, "id" to cards.getValue("meaning"), "preset_id" to preset)
        val windowPreset = s.mint(s.a, OpType.FSRS_PARAMS_CREATED, "id" to s.a.ids.newOpId(s.world.now), "name" to "window").p("id")!!
        val dOp = s.mint(s.a, OpType.CARD_PRESET_SET, "id" to existing, "preset_id" to windowPreset)
        repeat(5) { s.tick(HOUR); s.review(s.a, cards.getValue("reading")) }
        s.tick(DAY)
        s.sync(s.a)
        s.a.clock.fix()
        s.sync(s.a)
        val preview = s.a.app.rejected.previewRemint()
        assertTrue(preview.available, "preview: ${preview.blocked}")
        assertEquals(listOf(dOp.opId), preview.discards.filter { it.reason == DiscardReason.DEPENDS_ON_WINDOW_ROW }.map { it.row.opId }, "D is listed before confirming")
        assertNotNull(s.sim.remint(s.a))
        s.settleAndCheck("window rows")
        for (d in s.devices) {
            val t = d.tables()
            assertNotNull(t.get(LearningTable.DECK_ITEM, listOf(JsonString(deck), JsonString("n:$note"))), "${d.name}: the e:<seq> put was re-keyed n:<note>")
            assertNull(t.get(LearningTable.DECK_ITEM, listOf(JsonString(deck), JsonString("e:$entry"))), "${d.name}: no e:<seq> row")
            assertEquals("known", t.get(LearningTable.WORD_STATUS, listOf(kotobaya.contracts.sync.JsonNumber(entry.toDouble())))?.str("status"), "${d.name}: the status was re-minted")
            assertEquals(preset, t.get(LearningTable.CARD, listOf(JsonString(cards.getValue("meaning"))))?.str("preset_id"), "${d.name}: preset_set of a window card re-minted")
            assertEquals(Reserved.DEFAULT_PRESET_ID, t.get(LearningTable.CARD, listOf(JsonString(existing)))?.str("preset_id") ?: Reserved.DEFAULT_PRESET_ID, "${d.name}: D was discarded")
        }
        assertEquals(5, s.server.log().count { it.opType == OpType.REVIEW_LOGGED }, "the window's reviews were re-minted")
    }
}
