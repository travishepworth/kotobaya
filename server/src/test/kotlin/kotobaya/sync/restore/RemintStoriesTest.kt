package kotobaya.sync.restore

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.sync.ReplaySets
import kotobaya.sync.fuzz.ServerProjection
import kotobaya.sync.fuzz.SimDevice
import kotobaya.sync.fuzz.remintTimes
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC.md 12.6 (`remint_story`) and 12.7 (`ahead_story`, the clock-ahead re-mint scenario) from
 * `contracts/sync/tools/validate.py`, with the real server: the server's clock rejections, its
 * acceptance of the re-minted ops (old HLCs, reviews before their card), the merge of a re-minted
 * duplicate (8.1), the replay order by `reviewed_at` (9.1) through the real apply path, and
 * convergence of the server with both phones.
 */
class RemintStoriesTest {
    private val start = 0x0193a1b2c000L
    private val s = StoryServer(start)

    @AfterTest fun close() = s.close()

    private var real = start

    private fun tick(ms: Long, a: SimDevice, b: SimDevice, skewA: Long = 0) {
        real += ms
        s.pt = real; b.pt = real; a.pt = real + skewA
    }

    private fun review(d: SimDevice, card: String, utc: Int = 540): String = d.newId(3).also {
        d.make(
            OpType.REVIEW_LOGGED,
            mapOf("id" to it, "card_id" to card, "reviewed_at" to d.pt, "utc_offset_min" to utc, "review_type" to "scheduled", "rating" to 3, "button" to "good"),
        )
    }

    /** `reviewed_at` of card [card]'s replay set on the server, in replay order (9.1). */
    private fun serverReplayTimes(card: String): List<Long> = s.db.txBlocking { dsl ->
        ReplaySets.ids(dsl, UUID.fromString(card)).map { id ->
            (dsl.fetchValue("select (extract(epoch from reviewed_at) * 1000)::bigint from app.review_log where id = ?", id) as Number).toLong()
        }
    }

    private fun reviewIds(): Set<String> = s.db.txBlocking { dsl -> dsl.fetch("select id::text from app.review_log").map { it.get(0, String::class.java) }.toSet() }

    private fun assertConverged(label: String, vararg ds: SimDevice) {
        val sp = ServerProjection.of(s.db)
        for (d in ds) {
            assertEquals(emptyList(), ServerProjection.diff(sp, d.r.projection()), "$label: device ${d.id.takeLast(2)} converges")
            assertTrue(!d.rebuildPending && d.outbox.all { it.state == "acked" }, "$label: device ${d.id.takeLast(2)} has unfinished work")
        }
    }

    @Test
    fun workedExample12_6_aWeekOfReviewsWithAResetClockIsReMinted() {
        val a = SimDevice(A, guard = false) // a client without the 3.4 guards
        val b = SimDevice(B)
        tick(1000, a, b)
        val cards = (0 until 20).map { k ->
            val note = a.newId(1)
            val card = a.newId(2)
            a.make(OpType.NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 1358280 + k))
            a.make(OpType.CARD_CREATED, mapOf("id" to card, "note_id" to note, "kind" to "meaning"))
            card
        }
        a.sync(s.sim); b.sync(s.sim)
        val skew = Y2K - (real + 1000)
        // A client *with* the guards refuses the first review (G1).
        val probe = SimDevice(A).also { it.hlcLast = a.hlcLast; it.pt = Y2K }
        assertFailsWith<IllegalStateException> { review(probe, cards[0]) }

        val truth = linkedMapOf<String, Long>()
        var undone: String? = null
        for (day in 0 until 7) {
            for (k in 0 until 100) {
                tick(600_000, a, b, skew)
                val rid = review(a, cards[k % 19])
                truth[rid] = real
                if (day == 2 && k == 5) {
                    a.make(OpType.REVIEW_UNDONE, mapOf("review_id" to rid)); undone = rid
                }
            }
            tick(1000, a, b, skew)
            review(b, cards[19], utc = 0)
            b.make(OpType.CARD_SUSPENDED, mapOf("id" to cards[day]))
            b.sync(s.sim)
            tick(Reserved.DAY_MS - 100 * 600_000 - 1000, a, b, skew)
        }
        a.sync(s.sim) // online, clock still wrong: every review is refused
        val codes = a.outbox.filter { it.op["op_type"].asText() == "review.logged" && it.state == "rejected" }.map { it.lastError }.toSet()
        assertEquals(setOf("clock_behind"), codes)
        assertTrue(a.remintOffset != null)
        tick(1000, a, b) // the user fixes the clock
        a.sync(s.sim)
        a.remint()
        tick(1000, a, b)
        a.sync(s.sim)
        b.sync(s.sim)

        assertConverged("12.6", a, b)
        val ids = reviewIds()
        assertTrue(truth.keys.none { it in ids || it in a.r.t.getValue("review_log") }, "no original (clock-rejected) review id reached a replica")
        val want = truth.filterKeys { it != undone }.values.sorted()
        assertEquals(want, cards.take(19).flatMap(::serverReplayTimes).sorted(), "replay uses the real answer times")
        assertEquals(699, cards.take(19).sumOf { serverReplayTimes(it).size })
        for (c in cards.take(19)) assertEquals(serverReplayTimes(c).sorted(), serverReplayTimes(c), "replay set in reviewed_at order")
        // A review dated 25 h before its card is accepted (no card-age check, 3.4).
        val late = SimDevice(C, guard = false).also { it.r = b.r.copy(); it.pt = (a.r.t.getValue("card").getValue(cards[0])["created_at"] as Long) - 25 * 3_600_000L }
        review(late, cards[0], utc = 0)
        late.push(s.sim)
        assertEquals("acked", late.outbox.single().state)
        // The shift itself (10.4 step 5): one delta per batch, spacing kept unless the batch does not fit.
        val t26 = 1_780_000_000_000L
        assertEquals(t26 - Y2K to listOf(t26, t26 + 5), remintTimes(listOf(Y2K to t26, Y2K + 5 to t26), 0, t26 + 100))
        assertEquals(listOf(50L, 100L), remintTimes(listOf(0L to 50L, 200L to 50L), 0, 100).second)
        assertEquals(listOf(300L, 300L), remintTimes(listOf(500L to 400L, 510L to 400L), -10_000, 300).second)
    }

    @Test
    fun workedExample12_7_aWeekOfNewWordsWithAClockThatIsAheadIsReMinted() {
        val a = SimDevice(A)
        val b = SimDevice(B)
        val skew = 10 * Reserved.DAY_MS
        tick(1000, a, b)
        val oldNote = a.newId(1)
        val oldCard = a.newId(2)
        a.make(OpType.NOTE_CREATED, mapOf("id" to oldNote, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 1000000))
        a.make(OpType.CARD_CREATED, mapOf("id" to oldCard, "note_id" to oldNote, "kind" to "meaning"))
        a.sync(s.sim); b.sync(s.sim)
        tick(1000, a, b, skew) // A's clock jumps 10 days ahead; G1/G2 cannot see it
        val notes = mutableListOf<String>()
        val cards = mutableListOf<String>()
        val born = mutableMapOf<String, Long>()
        val shared = 2000000L + 42
        for (k in 0 until 100) {
            tick(1000, a, b, skew)
            val note = a.newId(1)
            val card = a.newId(2)
            a.make(OpType.NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 2000000 + k))
            a.make(OpType.CARD_CREATED, mapOf("id" to card, "note_id" to note, "kind" to "meaning"))
            a.make(OpType.NOTE_UPDATED, mapOf("id" to note, "set" to mapOf("fields" to mapOf("meaning" to "a$k", "reading" to "r$k"))))
            notes += note; cards += card; born[note] = real
        }
        a.make(OpType.CARD_SUSPENDED, mapOf("id" to oldCard)) // an edit of a row the server has: discard only
        val truth = linkedMapOf<String, Long>()
        var undone: String? = null
        val bNote = b.newId(1)
        val bCard = b.newId(2)
        for (day in 0 until 7) {
            for (k in 0 until 100) {
                tick(600_000, a, b, skew)
                val rid = review(a, cards[k])
                truth[rid] = real
                if (day == 4 && k == 7) {
                    a.make(OpType.REVIEW_UNDONE, mapOf("review_id" to rid)); undone = rid
                }
            }
            tick(1000, a, b, skew)
            if (day == 1) {
                b.make(OpType.NOTE_CREATED, mapOf("id" to bNote, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to shared))
                b.make(OpType.CARD_CREATED, mapOf("id" to bCard, "note_id" to bNote, "kind" to "meaning"))
            }
            if (day == 2) b.make(OpType.NOTE_UPDATED, mapOf("id" to bNote, "set" to mapOf("fields" to mapOf("meaning" to "from B"))))
            if (day >= 1) review(b, bCard, utc = 0)
            b.sync(s.sim)
            tick(Reserved.DAY_MS - 100 * 600_000 - 1000, a, b, skew)
        }
        a.sync(s.sim) // online, clock still 10 days ahead
        val codes = a.outbox.filter { it.state == "rejected" }.groupingBy { it.op["op_type"].asText() to it.lastError }.eachCount()
        assertEquals(
            mapOf<Pair<String, String?>, Int>(
                ("note.created" to "clock_ahead") to 100, ("card.created" to "unknown_reference") to 100,
                ("note.updated" to "unknown_reference") to 100, ("card.suspended" to "clock_ahead") to 1,
                ("review.logged" to "unknown_reference") to 700, ("review.undone" to "unknown_reference") to 1,
            ),
            codes,
        )
        assertEquals(-skew, a.remintOffset)
        tick(1000, a, b) // the user fixes the clock
        a.sync(s.sim)
        a.remint()
        tick(1000, a, b)
        a.sync(s.sim)
        b.sync(s.sim)

        assertConverged("12.7", a, b)
        val n = ServerProjection.of(s.db)
        @Suppress("UNCHECKED_CAST")
        fun note(id: String) = n.getValue("note").getValue(id) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        fun card(id: String) = n.getValue("card").getValue(id) as Map<String, Any?>
        for ((k, pair) in notes.zip(cards).withIndex()) {
            val (nid, cid) = pair
            if (2000000L + k == shared) {
                assertEquals(bNote, note(nid)["merged_into_id"], "the re-minted duplicate merges into B's note (8.1)")
                assertEquals(bCard, card(cid)["merged_into_id"], "and its card into B's card")
                continue
            }
            assertEquals(mapOf("meaning" to "a$k", "reading" to "r$k"), note(nid)["fields"], "edit of offline note $k kept")
            assertNull(note(nid)["deleted_at"])
            assertEquals(born[nid], note(nid)["created_at"], "note $k created_at is the real creation time")
        }
        assertEquals(mapOf("meaning" to "from B", "reading" to "r42"), note(bNote)["fields"], "B's later edit wins; A's other field forwarded")
        assertEquals(false, card(oldCard)["suspended"], "the clock_ahead edit of an existing card is discarded")
        val want = truth.filterKeys { it != undone }.values.sorted()
        val serverReviews = s.db.txBlocking { dsl ->
            dsl.fetch("select id::text, device_id::text from app.review_log").associate { it.get(0, String::class.java) to it.get(1, String::class.java) }
        }
        val got = cards.filter { card(it)["merged_into_id"] == null }.flatMap(::serverReplayTimes) +
            s.db.txBlocking { dsl ->
                ReplaySets.ids(dsl, UUID.fromString(bCard)).map(UUID::toString).filter { serverReviews[it] == A }
            }.map { id -> a.r.t.getValue("review_log").getValue(id)["reviewed_at"] as Long }
        assertEquals(want, got.sorted(), "replay uses the real review times")
        val times = serverReplayTimes(bCard)
        assertEquals(times.sorted(), times, "the survivor's replay set interleaves A's and B's reviews by reviewed_at")
        assertEquals(7 + 6, times.size)
        val ids = reviewIds()
        assertTrue(truth.keys.none { it in ids }, "no original (rejected) review id reached the server")
    }

    companion object {
        const val A = "01931f3e-0000-7000-8000-0000000000a1"
        const val B = "01931f3e-0000-7000-8000-0000000000b2"
        const val C = "01931f3e-0000-7000-8000-0000000000c3"
        const val Y2K = 946_684_800_000L
    }
}
