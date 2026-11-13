package kotobaya.sync.client.remint

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.diffRows
import kotobaya.sync.client.apply.projection
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.SyncStateKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC.md 12.6 and 12.7 end to end against the T303a test server (judging references, review
 * clocks and merges): after Re-mint + push + rebuild + pull the device, the server and the other
 * phone agree, and no review is lost.
 */
class RemintWorkedExamplesTest {
    private val a1 = "01931f3e-0000-7000-8000-0000000000a1"
    private val b2 = "01931f3e-0000-7000-8000-0000000000b2"
    private val t0 = 1_733_584_666_624L

    private fun reviewRows(t: kotobaya.sync.client.apply.LearningTables, device: String) =
        t.all(LearningTable.REVIEW_LOG).filter { it["device_id"] == JsonString(device) }

    private fun assertConverged(js: JudgingServer, vararg devices: RemintDevice) {
        val want = projection(js.tables)
        for (d in devices) assertEquals(emptyList(), diffRows(want, projection(d.d.tables())), "${d.d.deviceId} diverged")
    }

    /** 12.6: a week of reviews with a clock reset to 2000-01-01 (a client without G1/G2). */
    @Test
    fun aWeekOfReviewsWithAResetClock() {
        val js = JudgingServer(t0)
        val a = RemintDevice(a1, js.server, t0, 1)
        val b = RemintDevice(b2, js.server, t0, 2)
        val ids = RowIds(11)
        val cards = (0 until 20).map { i ->
            val note = ids.next(t0)
            val card = ids.next(t0)
            a.mint(OpType.NOTE_CREATED, vocabNote(note, 1_000_000L + i))
            a.mint(OpType.CARD_CREATED, obj("id" to card, "note_id" to note, "kind" to "meaning"))
            card
        }
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        // the RTC resets to 2000-01-01 one hour later
        val reset = t0 + HOUR
        val wrong = reset - 946_684_800_000L
        a.d.clock.now = reset - wrong
        assertEquals(ClockGuardResult.G1_BEFORE_FLOOR, (assertIs<MintResult.Refused>(a.d.minter.mint(OpType.SETTINGS_SET, obj("key" to "x", "value" to "y"))).reason as MintRefusal.ClockGuard).guard, "a client with the guards refuses (G1)")
        val real = ArrayList<Pair<String, Long>>()
        for (k in 0 until 700) {
            val at = reset + k * (7 * DAY / 700) + 1_000
            a.d.clock.now = at - wrong
            val id = ids.next(at)
            a.mintUnguarded(OpType.REVIEW_LOGGED, review(id, cards[k % 20], a.d.clock.now, if (k % 9 == 0) 1 else 3))
            real += id to at
            // B: daily, reviews card 20 and suspends a card
            if (k % 100 == 50) {
                b.d.clock.now = at
                js.now = at
                b.mint(OpType.REVIEW_LOGGED, review(ids.next(at), cards[19], at))
                b.mint(OpType.CARD_SUSPENDED, obj("id" to cards[k / 100]))
                assertIs<CycleResult.Completed>(b.cycle())
            }
        }
        val undone = real[350].first
        a.mintUnguarded(OpType.REVIEW_UNDONE, obj("review_id" to undone))
        // A pushes with the clock still wrong
        val end = reset + 7 * DAY
        js.now = end
        a.d.clock.now = end - wrong
        assertIs<CycleResult.Completed>(a.cycle())
        assertEquals(701, a.service.count())
        assertEquals(700, a.service.list().count { it.code == "clock_behind" })
        assertEquals(wrong.toString(), a.d.state(SyncStateKeys.REMINT_OFFSET))
        // the user fixes the clock; the next response shows it correct
        a.d.clock.now = end + MINUTE
        js.now = end + MINUTE
        assertIs<CycleResult.Completed>(a.cycle())
        val preview = a.service.previewRemint()
        assertNull(preview.blocked)
        assertEquals(699, preview.remintedReviews)
        assertEquals(listOf(DiscardReason.UNDONE_REVIEW, DiscardReason.UNDO_OF_REMINTED_REVIEW), preview.discards.map { it.reason })
        val out = assertIs<RemintOutcome.Reminted>(a.service.remint(preview))
        assertEquals(701, out.deleted.size)
        assertEquals(wrong, out.delta)
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        assertConverged(js, a, b)
        // no original review id anywhere; the 699 reviewed_at are exactly the real answer times
        val originals = real.map { it.first }.toSet()
        for (t in listOf(js.tables, a.d.tables(), b.d.tables())) {
            assertTrue(t.all(LearningTable.REVIEW_LOG).none { (it["id"] as JsonString).value in originals })
        }
        val got = reviewRows(js.tables, a1).map { (it["reviewed_at"] as JsonNumber).value.toLong() }.sorted()
        assertEquals(real.filter { it.first != undone }.map { it.second }.sorted(), got)
        assertEquals(699, out.reviewIds.size)
        assertTrue(undone !in out.reviewIds)
        assertEquals(7, reviewRows(js.tables, b2).size, "B's reviews kept")
    }

    /** 12.7: a week of new words with a clock 10 days ahead; one word is merged into B's. */
    @Test
    fun aWeekOfNewWordsWithAClockThatIsAhead() {
        val start = t0 - DAY
        val js = JudgingServer(start)
        val a = RemintDevice(a1, js.server, start, 1)
        val b = RemintDevice(b2, js.server, start, 2)
        val ids = RowIds(12)
        // an old card both phones have
        val oldNote = ids.next(start)
        val oldCard = ids.next(start)
        a.mint(OpType.NOTE_CREATED, vocabNote(oldNote, 999_999L))
        a.mint(OpType.CARD_CREATED, obj("id" to oldCard, "note_id" to oldNote, "kind" to "meaning"))
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())

        val ahead = 10 * DAY
        val shared = 7 // the word B also adds ("#42")
        val sharedEntry = 2_000_000L + shared
        class Word(val note: String, val card: String, val createdAt: Long)
        val words = ArrayList<Word>()
        val reviewTimes = ArrayList<Pair<String, Long>>()
        var bNote = ""
        var bCard = ""
        val events = ArrayList<Pair<Long, () -> Unit>>()
        for (i in 0 until 100) {
            val at = t0 + i * (DAY * 6 / 100)
            events += at to {
                a.d.clock.now = at + ahead
                val note = ids.next(at)
                val card = ids.next(at)
                a.mint(OpType.NOTE_CREATED, vocabNote(note, 2_000_000L + i, "expression" to "w$i", "meaning" to "a-created-$i"))
                a.mint(OpType.CARD_CREATED, obj("id" to card, "note_id" to note, "kind" to "meaning"))
                words += Word(note, card, at)
                a.d.clock.now = at + MINUTE + ahead
                a.mint(OpType.NOTE_UPDATED, obj("id" to note, "set" to obj("fields" to obj("reading" to "r$i", "meaning" to "a-edit-$i"))))
            }
        }
        events += (t0 + 3 * DAY) to {
            a.d.clock.now = t0 + 3 * DAY + ahead
            a.mint(OpType.CARD_SUSPENDED, obj("id" to oldCard))
        }
        for (k in 0 until 700) {
            val at = t0 + 2 * MINUTE + k * (DAY * 7 / 700)
            events += at to {
                val candidates = words.filter { it.createdAt + MINUTE < at }
                val w = candidates[(k * 7919) % candidates.size]
                a.d.clock.now = at + ahead
                val id = ids.next(at)
                a.mint(OpType.REVIEW_LOGGED, review(id, w.card, at + ahead, if (k % 11 == 0) 1 else 3))
                reviewTimes += id to at
            }
        }
        // B: day 1 adds the shared word, day 2 edits its meaning; daily reviews of it
        events += (t0 + DAY) to {
            b.d.clock.now = t0 + DAY
            js.now = t0 + DAY
            bNote = ids.next(t0 + DAY)
            bCard = ids.next(t0 + DAY)
            b.mint(OpType.NOTE_CREATED, vocabNote(bNote, sharedEntry, "expression" to "w$shared", "meaning" to "b-created"))
            b.mint(OpType.CARD_CREATED, obj("id" to bCard, "note_id" to bNote, "kind" to "meaning"))
            assertIs<CycleResult.Completed>(b.cycle())
        }
        events += (t0 + 2 * DAY) to {
            b.d.clock.now = t0 + 2 * DAY
            js.now = t0 + 2 * DAY
            b.mint(OpType.NOTE_UPDATED, obj("id" to bNote, "set" to obj("fields" to obj("meaning" to "b-edit"))))
            assertIs<CycleResult.Completed>(b.cycle())
        }
        for (d in 1 until 7) {
            val at = t0 + d * DAY + 2 * HOUR
            events += at to {
                b.d.clock.now = at
                js.now = at
                b.mint(OpType.REVIEW_LOGGED, review(ids.next(at), bCard, at))
                assertIs<CycleResult.Completed>(b.cycle())
            }
        }
        events.sortedBy { it.first }.forEach { it.second() }
        val undone = reviewTimes[400].first
        a.mint(OpType.REVIEW_UNDONE, obj("review_id" to undone))

        // A pushes everything with the clock still wrong
        val end = t0 + 7 * DAY
        js.now = end
        a.d.clock.now = end + ahead
        assertIs<CycleResult.Completed>(a.cycle())
        val codes = a.service.list().groupingBy { it.code }.eachCount()
        assertEquals(mapOf<String?, Int>("clock_ahead" to 101, "unknown_reference" to 100 + 100 + 700 + 1), codes)
        // the user fixes the clock; Re-mint (default)
        a.d.clock.now = end + MINUTE
        js.now = end + MINUTE
        assertIs<CycleResult.Completed>(a.cycle())
        val preview = a.service.previewRemint()
        assertNull(preview.blocked)
        assertEquals(300, preview.remintedOps)
        assertEquals(699, preview.remintedReviews)
        assertEquals(
            listOf(DiscardReason.CLOCK_EDIT, DiscardReason.UNDO_OF_REMINTED_REVIEW, DiscardReason.UNDONE_REVIEW),
            preview.discards.sortedBy { it.reason }.map { it.reason },
        )
        val out = assertIs<RemintOutcome.Reminted>(a.service.remint(preview))
        assertEquals(-ahead, out.delta)
        assertIs<CycleResult.Completed>(a.cycle())
        assertIs<CycleResult.Completed>(b.cycle())
        assertIs<CycleResult.Completed>(a.cycle())
        assertConverged(js, a, b)

        val t = js.tables
        // every offline note and card exists, with A's values and created_at = the real creation time
        for ((i, w) in words.withIndex()) {
            val note = t.get(LearningTable.NOTE, listOf(JsonString(w.note)))!!
            assertEquals(w.createdAt.toDouble(), (note["created_at"] as JsonNumber).value, "note $i created_at")
            assertEquals(JsonString("r$i"), (note["fields"] as JsonObject).members["reading"])
            if (i != shared) assertEquals(JsonNull, note["merged_into_id"] ?: JsonNull)
            assertEquals(w.createdAt.toDouble(), (t.get(LearningTable.CARD, listOf(JsonString(w.card)))!!["created_at"] as JsonNumber).value)
        }
        // the shared word: a merge loser of B's note; meaning is B's (later in real time), reading A's (forwarded)
        val loser = t.get(LearningTable.NOTE, listOf(JsonString(words[shared].note)))!!
        assertEquals(JsonString(bNote), loser["merged_into_id"])
        val survivor = (t.get(LearningTable.NOTE, listOf(JsonString(bNote)))!!["fields"] as JsonObject).members
        assertEquals(JsonString("b-edit"), survivor["meaning"])
        assertEquals(JsonString("r$shared"), survivor["reading"])
        // the suspend of the existing card is discarded
        assertEquals(kotobaya.contracts.sync.JsonBool(false), t.get(LearningTable.CARD, listOf(JsonString(oldCard)))!!["suspended"])
        // 699 reviews at exactly the real answer times; no original id on the server
        val originals = reviewTimes.map { it.first }.toSet()
        assertTrue(t.all(LearningTable.REVIEW_LOG).none { (it["id"] as JsonString).value in originals })
        val got = reviewRows(t, a1).map { (it["reviewed_at"] as JsonNumber).value.toLong() }.sorted()
        assertEquals(reviewTimes.filter { it.first != undone }.map { it.second }.sorted(), got)
        // the survivor's replay set interleaves A's and B's reviews in answer order
        val set = DeviceCardCache().replaySet(t, bCard)
        val byId = t.all(LearningTable.REVIEW_LOG).associateBy { (it["id"] as JsonString).value }
        val times = set.map { (byId.getValue(it.id)["reviewed_at"] as JsonNumber).value }
        assertEquals(times.sorted(), times)
        val devices = set.map { (byId.getValue(it.id)["device_id"] as JsonString).value }.toSet()
        assertEquals(setOf(a1, b2), devices)
        assertEquals(Reserved.SERVER_DEVICE_ID, js.server.loggedOps().first { it.opType == OpType.NOTE_MERGED }.deviceId)
    }
}
