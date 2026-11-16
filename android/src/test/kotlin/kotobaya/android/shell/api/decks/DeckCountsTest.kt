package kotobaya.android.shell.api.decks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.decks.DeckRepository
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.QueueCounts
import kotobaya.android.shell.api.decks.DecksTestSupport.SEQ_A
import kotobaya.android.shell.api.decks.DecksTestSupport.SEQ_B
import kotobaya.android.shell.api.decks.DecksTestSupport.call
import kotobaya.android.shell.api.decks.DecksTestSupport.device
import kotobaya.android.shell.api.decks.DecksTestSupport.items
import kotobaya.android.shell.api.decks.DecksTestSupport.json
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNumber
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L7 acceptance `DeckCountsTest` (ADR-037: deck counts come from the core review loop). `Deck` carries no
 * counts (openapi), so the SPA's per-deck numbers are `getReviewQueue?deck_id=` and the headline is `getReviewQueue`;
 * the native Decks screen reads [DeckRepository]. On the parity seed (plus a second, disjoint user deck) all of them
 * come from the same `LocalQueueLoader` build and agree:
 *
 * - the native overview's queue equals `getReviewQueue.counts`, and each native deck's counts equal
 *   `getReviewQueue?deck_id=<deck>.counts` (no second count implementation);
 * - sum relation: for every field, each deck's count ≤ the whole queue's ≤ the sum over the decks (the decks are
 *   disjoint and every card is in one; the day's budgets cap the whole queue, never raise it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DeckCountsTest {
    private fun ParityDevice.counts(query: String = ""): QueueCounts {
        val c = ParityJson.obj(call("GET", "/api/v1/review/queue", query = query).json().members["counts"])
        fun n(k: String) = (c.members[k] as JsonNumber).value.toInt()
        return QueueCounts(due = n("review"), new = n("new"), learning = n("learning"))
    }

    private fun QueueCounts.fields(): List<Int> = listOf(due, new, learning)

    @Test
    fun deckCountsAndTheQueueAgreeAndKeepTheSumRelation() {
        val d = device()
        val extra = "01a1f82f-0000-7000-8000-00000000d00e"
        assertEquals(201, d.call("POST", "/api/v1/decks", """{"id":"$extra","type":"user","name":"Extra"}""").status)
        assertEquals(200, d.call("POST", "/api/v1/decks/$extra/items", """{"items":[{"entry_seq":$SEQ_A},{"entry_seq":$SEQ_B}]}""").status)
        val deckIds = d.call("GET", "/api/v1/decks").items().map { ParityJson.str(it.members["id"]) }
        assertEquals(2, deckIds.size)

        // settle C-27 materialization (each fetch may create the notes of drawn entries), then read stable numbers
        var global = d.counts()
        var perDeck = deckIds.associateWith { d.counts("deck_id=$it") }
        repeat(3) {
            val g = d.counts()
            val p = deckIds.associateWith { d.counts("deck_id=$it") }
            if (g == global && p == perDeck) return@repeat
            global = g
            perDeck = p
        }
        assertEquals(global, d.counts())
        assertTrue(global.new > 0, "the seed has new cards: $global")

        // the native Decks screen over the same replica and loader
        val loader = LocalQueueLoader(
            db = d.database,
            clock = { d.clock.now },
            headwordOf = { seq -> runCatching { d.installedDictionary.entry(seq)?.headword }.getOrNull() },
            materializer = d.noteCreator,
        )
        val overview = DeckRepository(d.driver, loader).overview()
        assertEquals(global.fields(), overview.queue.fields(), "Today (native) = getReviewQueue")
        assertEquals(deckIds, overview.decks.map { it.id })
        overview.decks.forEach { deck ->
            assertEquals(perDeck.getValue(deck.id).fields(), deck.counts.fields(), "deck ${deck.name}: native = getReviewQueue?deck_id")
        }

        val sums = perDeck.values.map { it.fields() }.reduce { a, b -> a.zip(b) { x, y -> x + y } }
        for (i in 0 until 3) {
            perDeck.values.forEach { c -> assertTrue(c.fields()[i] <= global.fields()[i], "deck $c exceeds the queue $global") }
            assertTrue(global.fields()[i] <= sums[i], "queue $global exceeds the sum over decks $sums")
        }
    }
}
