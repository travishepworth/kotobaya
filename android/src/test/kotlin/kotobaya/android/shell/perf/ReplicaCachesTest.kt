package kotobaya.android.shell.perf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotobaya.android.queue.CandidateRow
import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.stats.StatsHistoryCache
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PERF-LF: the caches kept between requests never serve stale replica data. After every kind of write (a tracked card
 * update, an answer through the op path, a pulled review, an undo, a card merge, a note change, a raw statement the
 * store cannot attribute) the cached queue candidates and stats history equal what a fresh read computes, and the
 * change versions say exactly what changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class ReplicaCachesTest {
    private val now = 1_795_000_000_000L
    private val d = ParityDevice("01931f3e-0000-7000-8000-0000000000d5", now, LocalRoutes.all)
    private val small = ReplicaScale.Size(vocabNotes = 300, kanjiNotes = 60, listDecks = 2, listDeckItems = 200, minedDeckItems = 40)

    init {
        ReplicaScale.seed(d.driver, d.store, now, small)
    }

    private val q get() = d.database.localQueueQueries

    private fun fresh(): List<CandidateRow> = q.queueCandidatesAll(Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList().sortedBy { it.id }

    private fun cached(): List<CandidateRow> = d.queueCache.candidates(q).sortedBy { it.id }

    /** A canonical text of a stats snapshot (every root's day reviews and replayed reviews). */
    private fun statsText(cache: StatsHistoryCache): String = d.store.read { tx ->
        val snap = cache.snapshot(d.database, tx.learning)
        snap.roots.toSortedMap().entries.joinToString("\n") { (root, h) ->
            root + " " + h.days.joinToString(";") { "${it.reviewedAt},${it.utcOffsetMin},${it.type},${it.rating},${it.durationMs},${it.introduced}" } +
                " | " + h.scheduled.joinToString(";") { "${it.reviewedAt},${it.passed},${it.intervalBefore}" }
        }
    }

    private fun assertCachesCurrent(what: String) {
        assertEquals(fresh(), cached(), "$what: queue candidates")
        assertEquals(statsText(StatsHistoryCache(null)), statsText(d.statsCache), "$what: stats history")
    }

    private val cards: List<String> by lazy { fresh().filter { it.state != "new" }.map { it.id } }

    private fun review(id: String, card: String, at: Long, rating: Int = 3) = mapOf(
        "id" to JsonString(id), "card_id" to JsonString(card), "device_id" to JsonString("00000000-0000-7000-8000-000000000001"),
        "hlc" to JsonString("%013d-000000-test".format(at)), "reviewed_at" to JsonNumber(at.toDouble()), "review_type" to JsonString("scheduled"),
        "rating" to JsonNumber(rating.toDouble()), "button" to JsonString(if (rating == 1) "again" else "good"), "duration_ms" to JsonNumber(3000.0),
    )

    @Test
    fun cachesFollowEveryKindOfWrite() {
        assertCachesCurrent("first build")
        val queueFull = d.queueCache.fullLoads
        val statsFull = d.statsCache.fullLoads

        // a tracked card update: only that card is read again
        val c0 = cards[0]
        val v = d.store.versions.cardVersion()
        d.store.transaction { it.learning.put(LearningTable.CARD, mapOf("id" to JsonString(c0), "due_at" to JsonNumber((now + 1000).toDouble()), "updated_at" to JsonNumber(now.toDouble()))) }
        assertEquals(setOf(c0), d.store.versions.changedCards(v))
        assertCachesCurrent("card update")
        assertEquals(queueFull, d.queueCache.fullLoads, "a card update refreshes only that card")

        // a pulled review (new review_log row and the card's schedule), then another device's older review out of order
        d.store.transaction { tx ->
            tx.learning.put(LearningTable.REVIEW_LOG, review("0193dddd-0000-7000-8000-000000000001", cards[1], now - 5_000, rating = 1))
            tx.learning.put(LearningTable.CARD, mapOf("id" to JsonString(cards[1]), "lapses" to JsonNumber(9.0), "updated_at" to JsonNumber(now.toDouble())))
        }
        assertCachesCurrent("pulled review")
        d.store.transaction { it.learning.put(LearningTable.REVIEW_LOG, review("0193dddd-0000-7000-8000-000000000002", cards[1], now - 400 * ReplicaScale.DAY)) }
        assertCachesCurrent("an old review arriving late")
        assertEquals(statsFull, d.statsCache.fullLoads, "reviews refresh only their roots")

        // an undo of that review
        d.store.transaction {
            it.learning.put(LearningTable.REVIEW_LOG, mapOf("id" to JsonString("0193dddd-0000-7000-8000-000000000001"), "undone_at" to JsonNumber(now.toDouble()), "undone_hlc" to JsonString("u")))
        }
        assertCachesCurrent("undo")

        // a card merge: the loser's history moves to the survivor
        d.store.transaction {
            it.learning.put(LearningTable.CARD, mapOf("id" to JsonString(cards[2]), "merged_into_id" to JsonString(cards[3]), "deleted_at" to JsonNumber(now.toDouble())))
        }
        assertCachesCurrent("card merge")

        // an answer through the local op path (review.logged minted, card cache replayed)
        val body = """{"id":"0193eeee-0000-7000-8000-000000000001","card_id":"${cards[4]}","rating":4,"duration_ms":4200,"utc_offset_min":540}"""
        assertEquals(200, d.request("POST", "/api/v1/review/answer", "", ParityJson.parse(body)).status)
        assertCachesCurrent("local answer")

        // a note change (note facts are part of every candidate row): everything is read again
        val noteId = fresh().first { it.type == "vocab" }.note_id
        d.store.transaction { it.learning.put(LearningTable.NOTE, mapOf("id" to JsonString(noteId), "deleted_at" to JsonNumber(now.toDouble()))) }
        assertCachesCurrent("note deleted")

        // a raw statement inside a store transaction: the store cannot attribute it, so every version moves
        val before = d.store.versions.version(LearningTable.CARD, LearningTable.REVIEW_LOG, LearningTable.DECK_ITEM)
        val cv = d.store.versions.cardVersion()
        d.store.transaction {
            d.driver.execute(null, "UPDATE card SET suspended = 1 WHERE id = ?", 1) { bindString(0, cards[5]) }
            d.driver.execute(null, "UPDATE review_log SET duration_ms = 1 WHERE card_id = ?", 1) { bindString(0, cards[6]) }
        }
        assertTrue(d.store.versions.version(LearningTable.CARD, LearningTable.REVIEW_LOG, LearningTable.DECK_ITEM) > before)
        assertNull(d.store.versions.changedCards(cv), "an untracked write has no key list")
        assertCachesCurrent("raw write")

        // reads and rolled-back writes change nothing
        val all = d.store.versions.version(*LearningTable.entries.toTypedArray())
        d.store.read { it.learning.all(LearningTable.CARD) }
        runCatching { d.store.transaction { tx -> tx.learning.put(LearningTable.CARD, mapOf("id" to JsonString(cards[7]), "suspended" to kotobaya.contracts.sync.JsonBool(true))); error("rollback") } }
        assertEquals(all, d.store.versions.version(*LearningTable.entries.toTypedArray()))
        assertCachesCurrent("rollback")
    }

    @Test
    fun manyRandomWritesKeepTheCachesCurrent() {
        val rnd = Random(3)
        assertCachesCurrent("first build")
        repeat(40) { i ->
            d.store.transaction { tx ->
                repeat(1 + rnd.nextInt(4)) { k ->
                    val card = cards[rnd.nextInt(cards.size)]
                    when (rnd.nextInt(3)) {
                        0 -> tx.learning.put(LearningTable.REVIEW_LOG, review("0193cccc-0000-7000-8000-%06x%06x".format(i, k), card, now - rnd.nextLong(30 * ReplicaScale.DAY), 1 + rnd.nextInt(4)))
                        1 -> tx.learning.put(LearningTable.CARD, mapOf("id" to JsonString(card), "due_at" to JsonNumber((now + rnd.nextLong(ReplicaScale.DAY)).toDouble()), "updated_at" to JsonNumber(now.toDouble())))
                        else -> tx.learning.put(LearningTable.CARD, mapOf("id" to JsonString(card), "suspended" to kotobaya.contracts.sync.JsonBool(rnd.nextBoolean())))
                    }
                }
            }
            if (i % 5 == 4) assertCachesCurrent("after write batch $i")
        }
        assertTrue(d.queueCache.partialLoads > 0 && d.statsCache.partialLoads > 0, "the partial refresh paths ran")
    }
}
