package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC.md 12.1–12.3 and 12.5–12.7 as scripted runs of the real client stack against the server, each
 * with the outcome the contract states, and then the full oracle (convergence, caches, zero lost
 * reviews). 12.4 (every scenario file) is [ScenarioFilesTest].
 */
class WorkedExamplesTest {
    private fun fields(r: Row): Map<String, Any?> = (r["fields"] as JsonObject).members

    @Test
    fun example12_1_twoDevicesReviewTheSameCardOffline() = Story().use { s ->
        val (_, cards) = s.word(s.a, 1358280)
        val c = cards.getValue("meaning")
        s.syncOk(s.a)
        s.syncOk(s.b)
        val t = s.world.now
        s.at(t + 10_000); val r1 = s.review(s.a, c, 3, "learning")
        s.at(t + 20_000); val r3 = s.review(s.b, c, 1, "scheduled")
        s.at(t + 25_000); val r4 = s.review(s.b, c, 3, "drill")
        s.at(t + 30_000); val r2 = s.review(s.a, c, 3, "scheduled")
        s.at(t + 40_000)
        assertIs<CycleResult.Completed>(s.a.pushOnly()) // A pushes first
        s.syncOk(s.b) // B pushes, then pulls A's reviews: full replay
        s.syncOk(s.a) // A pulls B's: B@20 sorts before A@30, full replay
        val want = listOf(r1, r3, r2).map { it.p("id") }
        for (d in s.devices) {
            assertEquals(want, s.replaySet(d, c), "${d.name}: nothing overwritten or dropped, replayed by reviewed_at")
            s.row(d, LearningTable.REVIEW_LOG, r4.p("id")!!) // the drill is logged only
        }
        s.settleAndCheck("12.1")
    }

    @Test
    fun example12_2_editVersusDelete() = Story().use { s ->
        val (n, cards) = s.word(s.a, 1358280)
        val c = cards.getValue("meaning")
        s.syncOk(s.a)
        s.syncOk(s.b)
        val t = s.world.now
        s.at(t + 10_000); s.mint(s.a, OpType.NOTE_UPDATED, "id" to n, "set" to mapOf("tags" to listOf("anime")))
        s.at(t + 20_000); val del = s.mint(s.b, OpType.NOTE_DELETED, "id" to n)
        s.at(t + 30_000); s.mint(s.a, OpType.NOTE_UPDATED, "id" to n, "set" to mapOf("fields" to mapOf("meaning" to "to eat")))
        s.at(t + 31_000); val r = s.review(s.a, c)
        s.at(t + 32_000); s.mint(s.a, OpType.CARD_SUSPENDED, "id" to c)
        s.at(t + 40_000)
        s.syncOk(s.b)
        s.syncOk(s.a)
        s.syncOk(s.b)
        for (d in s.devices) {
            val note = s.row(d, LearningTable.NOTE, n)
            assertEquals(JsonNumber(DefaultHlcRules.wall(del.hlc).toDouble()), note["deleted_at"], "${d.name}: delete is permanent")
            assertEquals(JsonArray(listOf(JsonString("anime"))), note["tags"], "${d.name}: older edit applied to the tombstone")
            assertEquals(JsonString("to eat"), fields(note)["meaning"], "${d.name}: newer edit applied, does not undelete")
            s.row(d, LearningTable.REVIEW_LOG, r.p("id")!!)
            assertEquals(JsonBool(true), s.row(d, LearningTable.CARD, c)["suspended"], d.name)
            assertTrue(s.sim.liveCards(d.tables()).none { it.str("id") == c }, "${d.name}: the card is hidden with its note")
        }
        s.settleAndCheck("12.2")
    }

    @Test
    fun example12_3_theSameWordAddedOnTwoDevices() = Story().use { s ->
        val entry = 1358280L
        val t = s.world.now
        s.at(t + 10_000)
        val (w, wCards) = s.word(s.a, entry, "meaning", extra = mapOf("mnemonic" to "tabby eats"))
        s.syncOk(s.a)
        // B is offline and has never seen W
        s.at(t + 20_000)
        val l = s.b.ids.newOpId(s.world.now)
        s.mint(s.b, OpType.NOTE_CREATED, "id" to l, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to entry, "fields" to mapOf("sentence" to "パンを食べる"))
        s.at(t + 21_000); val lMeaning = s.mint(s.b, OpType.CARD_CREATED, "id" to s.b.ids.newOpId(s.world.now), "note_id" to l, "kind" to "meaning").p("id")!!
        s.at(t + 22_000); val lReading = s.mint(s.b, OpType.CARD_CREATED, "id" to s.b.ids.newOpId(s.world.now), "note_id" to l, "kind" to "reading").p("id")!!
        s.at(t + 30_000); s.mint(s.b, OpType.NOTE_UPDATED, "id" to l, "set" to mapOf("tags" to listOf("anime")))
        s.at(t + 31_000); s.mint(s.b, OpType.CARD_SUSPENDED, "id" to lMeaning)
        s.at(t + 32_000); val rb = s.review(s.b, lMeaning)
        s.at(t + 33_000); val ra = s.review(s.a, wCards.getValue("meaning"))
        s.at(t + 40_000)
        s.syncOk(s.b)
        s.syncOk(s.a)
        s.syncOk(s.b)
        val wMeaning = wCards.getValue("meaning")
        for (d in s.devices) {
            assertEquals(JsonString(w), s.row(d, LearningTable.NOTE, l)["merged_into_id"], "${d.name}: L is merged into W")
            val wRow = s.row(d, LearningTable.NOTE, w)
            assertEquals(JsonString("tabby eats"), wRow["mnemonic"], "${d.name}: L's empty mnemonic has no clock, not forwarded")
            assertEquals(JsonString("パンを食べる"), fields(wRow)["sentence"], "${d.name}: forwarded from L")
            assertNull(fields(wRow)["meaning"], "${d.name}: L's \"\" was dropped at creation")
            assertEquals(JsonArray(listOf(JsonString("anime"))), wRow["tags"], "${d.name}: addressed to L after the merge, forwarded")
            assertEquals(JsonBool(true), s.row(d, LearningTable.CARD, wMeaning)["suspended"], "${d.name}: L's card suspend forwarded")
            assertEquals(JsonString(wMeaning), s.row(d, LearningTable.CARD, lMeaning)["merged_into_id"], d.name)
            val wReading = d.tables().where(LearningTable.CARD, "note_id", JsonString(w)).single { it.str("kind") == "reading" && it.isNull("merged_into_id") }
            assertEquals(JsonString(wReading.str("id")!!), s.row(d, LearningTable.CARD, lReading)["merged_into_id"], "${d.name}: the server created W's reading card")
            assertEquals(listOf(rb.p("id"), ra.p("id")), s.replaySet(d, wMeaning), "${d.name}: B@32 then A@33; review_log.card_id stays L's card")
        }
        assertTrue(s.server.log().any { it.opType == OpType.CARD_CREATED && it.deviceId == Reserved.SERVER_DEVICE_ID }, "server-created reading card")
        s.settleAndCheck("12.3")
    }

    @Test
    fun example12_5_serverRestoredFromABackup() = Story().use { s ->
        val (n, cards) = s.word(s.a, 1358280)
        val c = cards.getValue("meaning")
        s.syncOk(s.a) // seq 1, 2
        s.syncOk(s.b)
        s.sim.backup() // verified backup of seq 1..2: durable_seq = 2
        val epoch1 = s.a.state(SyncStateKeys.LOG_EPOCH)
        repeat(5) { s.tick(MINUTE); s.review(s.a, c) }
        s.syncOk(s.a) // seq 3..7, pulled
        assertEquals("2", s.a.state(SyncStateKeys.DURABLE_SEQ))
        assertEquals(5, s.a.count(PendingState.ACKED), "acked_seq 3..7 > durable_seq 2: rows kept")
        s.tick(HOUR)
        s.sim.restore() // disk lost: back to seq 2, new epoch, clock raised
        s.tick(MINUTE)
        s.mint(s.b, OpType.NOTE_UPDATED, "id" to n, "set" to mapOf("tags" to listOf("restored")))
        s.mint(s.b, OpType.CARD_SUSPENDED, "id" to c)
        s.sync(s.b)
        assertNotEquals(epoch1, s.b.state(SyncStateKeys.LOG_EPOCH), "B noticed the new epoch")
        s.tick(MINUTE)
        s.sync(s.a)
        assertNotEquals(epoch1, s.a.state(SyncStateKeys.LOG_EPOCH), "A noticed the new epoch")
        s.settleAndCheck("12.5")
        assertEquals(5, s.server.log().count { it.opType == OpType.REVIEW_LOGGED }, "server, A and B identical, all 5 reviews present")
    }

    @Test
    fun example12_5_1_restoredBelowTheDevicesPullSeq() = Story(n = 1).use { s ->
        val (n, cards) = s.word(s.a, 1358280)
        s.syncOk(s.a)
        s.sim.backup()
        s.syncOk(s.a) // purges (acked_seq <= 2 <= durable_seq)
        assertEquals(0, s.a.rows().size)
        repeat(8) { s.tick(1000); s.server.emit(OpType.NOTE_UPDATED, obj("id" to n, "set" to mapOf("mnemonic" to "rest $it"))) }
        s.syncOk(s.a)
        assertEquals("10", s.a.state(SyncStateKeys.PULL_SEQ))
        s.tick(HOUR)
        s.sim.restore() // back to seq 2
        s.tick(MINUTE)
        val r = s.review(s.a, cards.getValue("meaning"))
        val result = s.sync(s.a)
        assertIs<CycleResult.Completed>(result, "no livelock: the log reset set pull_seq to 0")
        assertEquals("3", s.a.state(SyncStateKeys.PULL_SEQ))
        assertNull(s.a.state(SyncStateKeys.REBUILD_PENDING))
        assertEquals(listOf(r.opId), s.server.log().filter { it.opType == OpType.REVIEW_LOGGED }.map { it.opId }, "the review is in the log once")
        s.tick(MINUTE)
        s.review(s.a, cards.getValue("meaning")) // A can review again
        s.settleAndCheck("12.5.1")
    }

    /** SYNC.md 12.6 (scaled: 20 notes, 70 reviews): an RTC reset to 2000 on a client without the guards; Re-mint. */
    @Test
    fun example12_6_aWeekOfReviewsWithAResetClock() = Story().use { s ->
        val cards = (0 until 20).map { i -> s.tick(1000); s.word(s.a, 1_400_000L + i).second.getValue("meaning") }
        s.syncOk(s.a)
        s.syncOk(s.b)
        val start = s.world.now
        s.a.clock.offset = 946_684_800_000L - s.world.now // RTC reset to 2000-01-01
        val guarded = s.a.mint(MintRequest(OpType.REVIEW_LOGGED, obj("id" to s.a.ids.newOpId(0), "card_id" to cards[0], "reviewed_at" to s.a.clock.nowMs(), "utc_offset_min" to 540, "review_type" to "scheduled", "rating" to 3)))
        assertIs<MintRefusal.ClockGuard>(assertIs<MintResult.Refused>(guarded).reason, "a client with the guards refuses the first review (G1)")
        val reviews = ArrayList<Op>()
        for (day in 0 until 7) {
            for (k in 0 until 10) {
                s.at(start + day * DAY + 8 * HOUR + k * 7 * MINUTE)
                val op = s.a.mintUnguarded(
                    OpType.REVIEW_LOGGED,
                    obj("id" to s.a.ids.newOpId(s.a.clock.nowMs()), "card_id" to cards[(day * 10 + k) % 20], "reviewed_at" to s.a.clock.nowMs(), "utc_offset_min" to 540, "review_type" to "scheduled", "rating" to 1 + (k % 4)),
                )
                s.sim.ledger.logged(op, s.a.id, s.world.now)
                reviews += op
            }
            if (day == 3) s.sim.ledger.undone(s.a.mintUnguarded(OpType.REVIEW_UNDONE, obj("review_id" to reviews.last().p("id"))))
            s.at(start + day * DAY + 20 * HOUR)
            s.review(s.b, cards[19])
            s.mint(s.b, OpType.CARD_SUSPENDED, "id" to cards[day])
            s.syncOk(s.b)
        }
        s.at(start + 7 * DAY + HOUR)
        s.sync(s.a) // clock still wrong
        val codes = s.a.rows().filter { it.state == PendingState.REJECTED }.groupingBy { it.lastError!!.substringBefore(':') }.eachCount()
        assertEquals(mapOf("clock_behind" to 70, "unknown_reference" to 1), codes)
        assertEquals((s.world.now - s.a.clock.nowMs()).toString(), s.a.state(SyncStateKeys.REMINT_OFFSET), "remint_offset = server_time - pt")
        s.a.clock.fix()
        s.sync(s.a) // the next response shows it correct
        val out = assertNotNull(s.sim.remint(s.a), "Re-mint available")
        assertEquals(69, out.reviewIds.size, "the undone review is not re-logged")
        assertEquals(71, out.deleted.size)
        assertEquals(2, out.discarded.size, "the undo and the undone review")
        s.settleAndCheck("12.6")
        val logged = s.server.log().filter { it.opType == OpType.REVIEW_LOGGED && it.deviceId == s.a.id }
        assertTrue(logged.none { it.p("id") in reviews.map { r -> r.p("id") } }, "no original review id exists on any replica")
        for (op in logged) {
            val rec = s.sim.ledger.reviews.getValue(op.p("id")!!)
            assertEquals(rec.trueTime, (op.payload.members["reviewed_at"] as JsonNumber).value.toLong(), "replayed reviewed_at = the real answer time")
        }
    }

    /** SYNC.md 12.7 (scaled: 10 words, 70 reviews): a clock 10 days ahead for an offline week of new words; Re-mint. */
    @Test
    fun example12_7_aWeekOfNewWordsWithAClockThatIsAhead() = Story().use { s ->
        val old = s.word(s.a, 1_300_000L).second.getValue("meaning")
        s.syncOk(s.a)
        s.syncOk(s.b)
        val start = s.world.now
        s.a.clock.offset = 10 * DAY
        val created = LinkedHashMap<String, Long>() // A's note id -> real creation time
        val words = (0 until 10).map { i ->
            s.at(start + i * MINUTE)
            val (note, cards) = s.word(s.a, 1_500_000L + i)
            created[note] = s.world.now
            s.mint(s.a, OpType.NOTE_UPDATED, "id" to note, "set" to mapOf("fields" to mapOf("meaning" to "A meaning $i", "reading" to "A reading $i")))
            note to cards.getValue("meaning")
        }
        s.mint(s.a, OpType.CARD_SUSPENDED, "id" to old)
        val shared = 4 // "word #42"
        var bNote = ""
        var bCard = ""
        val aReviews = ArrayList<Op>()
        for (day in 0 until 7) {
            for (k in 0 until 10) {
                s.at(start + day * DAY + 9 * HOUR + k * 11 * MINUTE)
                aReviews += s.review(s.a, words[(k + day) % 10].second, 1 + (k % 4))
            }
            if (day == 2) s.mint(s.a, OpType.REVIEW_UNDONE, "review_id" to aReviews.last().p("id"))
            s.at(start + day * DAY + 18 * HOUR)
            if (day == 1) {
                val (n, cs) = s.word(s.b, 1_500_000L + shared)
                bNote = n
                bCard = cs.getValue("meaning")
            }
            if (day == 2) s.mint(s.b, OpType.NOTE_UPDATED, "id" to bNote, "set" to mapOf("fields" to mapOf("meaning" to "B meaning")))
            if (day >= 1) s.review(s.b, bCard)
            s.syncOk(s.b)
        }
        s.at(start + 7 * DAY + HOUR)
        s.sync(s.a) // clock still wrong
        val codes = s.a.rows().filter { it.state == PendingState.REJECTED }.groupingBy { it.lastError!!.substringBefore(':') }.eachCount()
        assertEquals(mapOf("clock_ahead" to 11, "unknown_reference" to 91), codes, "note.created x10 + the suspend; cards, edits, reviews, the undo")
        s.a.clock.fix()
        s.sync(s.a)
        val out = assertNotNull(s.sim.remint(s.a))
        assertEquals(30 + 69, out.minted.size, "E = 30 creations/edits, R = 69 reviews")
        assertEquals(3, out.discarded.size, "the suspend of an existing card, the undo and the undone review")
        s.settleAndCheck("12.7")
        val ref = replicaOf(s.server.log())
        for ((note, real) in created) {
            val row = assertNotNull(ref.get(LearningTable.NOTE, listOf(JsonString(note))))
            if (row.isNull("merged_into_id")) assertEquals(JsonNumber(real.toDouble()), row["created_at"], "created_at = the real creation time")
        }
        val aShared = words[shared].first
        val loser = assertNotNull(ref.get(LearningTable.NOTE, listOf(JsonString(aShared))))
        assertEquals(JsonString(bNote), loser["merged_into_id"], "word #42 is a merge loser of B's note")
        val survivor = assertNotNull(ref.get(LearningTable.NOTE, listOf(JsonString(bNote))))
        assertEquals(JsonString("B meaning"), fields(survivor)["meaning"], "B's meaning was made later in real time")
        assertEquals(JsonString("A reading $shared"), fields(survivor)["reading"], "A's reading is forwarded")
        assertEquals(JsonBool(false), assertNotNull(ref.get(LearningTable.CARD, listOf(JsonString(old))))["suspended"], "the suspend was discarded")
        val set = kotobaya.sync.client.apply.DeviceCardCache().replaySet(ref, bCard).map { it.id }
        val byTrue = set.sortedBy { s.sim.ledger.reviews.getValue(it).trueTime }
        assertEquals(byTrue, set, "the survivor's replay set interleaves A's and B's reviews in answer order")
        assertTrue(set.any { s.sim.ledger.reviews.getValue(it).device == s.a.id } && set.any { s.sim.ledger.reviews.getValue(it).device == s.b.id })
        for (op in s.server.log().filter { it.opType == OpType.REVIEW_LOGGED && it.deviceId == s.a.id }) {
            assertEquals(s.sim.ledger.reviews.getValue(op.p("id")!!).trueTime, (op.payload.members["reviewed_at"] as JsonNumber).value.toLong(), "exact answer times")
        }
    }
}
