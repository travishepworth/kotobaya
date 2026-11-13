package kotobaya.android.demo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotobaya.android.review.ReviewState
import kotobaya.android.search.CardSearchOutcome
import kotobaya.android.search.WordSearchOutcome
import kotobaya.android.word.WordLoad
import kotobaya.sync.client.apply.LearningTable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI-FOUND-APP: the demo dataset loads from the debug assets into the isolated demo store with nothing dropped
 * (fixtures/demo/README: 65 notes / 108 cards, 31 decks, 6 media), and every screen of the catalogue exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DemoDataTest {
    private val assets get() = ApplicationProvider.getApplicationContext<Application>().assets

    @Test
    fun theDefaultScenarioSeedsEveryNoteAndCard() {
        val data = DemoData.load(assets)
        assertEquals(65, data.notes.size)
        assertEquals(108, data.cards.size)
        assertEquals(31, data.decks.size)
        assertEquals(6, data.media.size)
        val learning = DemoStore(data).store.learning()
        assertEquals(data.notes.size, learning.all(LearningTable.NOTE).size)
        assertEquals(data.cards.size, learning.all(LearningTable.CARD).size)
        assertEquals(data.decks.size, learning.all(LearningTable.DECK).size)
    }

    @Test
    fun theEmptyScenarioClearsAccountDataButKeepsTheDictionary() {
        val data = DemoData.load(assets, "empty")
        assertEquals(0, data.notes.size)
        assertEquals(0, data.decks.size)
        assertEquals(0, data.media.size)
        assertTrue(data.entries.isNotEmpty())
        assertTrue(data.noteTypes.isNotEmpty())
    }

    @Test
    fun theDemoReviewSessionShowsADueCardAndAnswersStayInMemory() {
        val store = DemoStore(DemoData.load(assets))
        val session = store.reviewBackend().newSession()
        session.start()
        val shown = assertIs<ReviewState.Showing>(session.state.value)
        assertTrue(session.answer(shown.card.token, kotobaya.contracts.fsrs.Button.GOOD))
        assertEquals(1, store.store.rows().size)
    }

    @Test
    fun thePortsAnswerFromTheDataset() = runBlocking {
        val data = DemoData.load(assets)
        val search = DemoSearchPort(data)
        val words = assertIs<WordSearchOutcome.Page>(search.words("to", null))
        assertTrue(words.page.items.isNotEmpty())
        val cards = assertIs<CardSearchOutcome.Results>(search.cards(""))
        assertEquals(data.cards.size, cards.hits.size)
        val toru = data.entries.first { it.optString("headword") == "取る" }.optLong("seq")
        val detail = assertIs<WordLoad.Found>(search.word(toru))
        assertEquals(17, detail.detail.senses.size)
        val decks = DemoDeckSource(data).decks()
        assertEquals(data.decks.size, decks.size)
        assertTrue(decks.sumOf { it.counts.due } > 0)
    }

    @Test
    fun theCatalogueListsEveryScreenWithAPreview() {
        assertEquals(SCREEN_NAMES, DemoScreens.all(DemoData.load(assets)).map { it.name })
    }
}
