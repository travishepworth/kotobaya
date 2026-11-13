package kotobaya.android.intake

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.ExistingNote
import kotobaya.android.notes.NoteRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeServices(var duplicate: Boolean = false) : IntakeServices {
    val requests = mutableListOf<NoteRequest>()

    override fun create(request: NoteRequest): CreateResult {
        requests += request
        return if (duplicate) {
            CreateResult.Duplicate(ExistingNote("n1", null, request.word, 0))
        } else {
            CreateResult.Created("n2", emptyList(), emptyList(), kotobaya.android.notes.MatchResult.NoMatch, emptyList())
        }
    }

    override fun decks(): List<DeckOption> = listOf(DeckOption("d1", "Mined"))
}

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class IntakeActivityTest {
    private val fake = FakeServices()

    @After
    fun tearDown() {
        IntakeServicesProvider.factory = { KoinIntakeServices() }
    }

    private fun resolves(intent: Intent) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val info = ctx.packageManager.resolveActivity(intent, 0)
        assertEquals(IntakeActivity::class.java.name, info?.activityInfo?.name, "intent $intent")
    }

    private fun start(intent: Intent): IntakeActivity {
        IntakeServicesProvider.factory = { fake }
        intent.setClass(ApplicationProvider.getApplicationContext(), IntakeActivity::class.java)
        return Robolectric.buildActivity(IntakeActivity::class.java, intent).create().get()
    }

    @Test
    fun shareCreatesTheNote() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "猫が寝ている。")
        resolves(Intent(intent))
        val a = start(intent)
        a.model.setWord("猫")
        a.model.setDeck("d1")
        a.model.submitNow()
        assertEquals(IntakeOutcome.Added, a.model.state.value.outcome)
        val r = fake.requests.single()
        assertEquals("猫", r.word)
        assertEquals("猫が寝ている。", r.sentence)
        assertEquals("share", r.origin)
        assertEquals("d1", r.deckId)
    }

    @Test
    fun processTextCreatesTheNote() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, "食べる")
        resolves(Intent(intent))
        val a = start(intent)
        a.model.submitNow()
        assertEquals("食べる", fake.requests.single().word)
    }

    @Test
    fun addLinkCreatesTheNoteAndADuplicateOffersOpenExisting() {
        val uri = Uri.parse("kotobaya://add?word=%E7%8C%AB&sentence=%E7%8C%AB")
        resolves(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        fake.duplicate = true
        val a = start(Intent(Intent.ACTION_VIEW, uri))
        a.model.submitNow()
        assertEquals(IntakeOutcome.Duplicate("n1"), a.model.state.value.outcome)
        assertEquals("deeplink", fake.requests.single().origin)
        a.model.openExisting()
        assertIs<IntakeOutcome.WordPage>(a.model.state.value.outcome)
    }

    @Test
    fun wordLinkOpensTheStubAndBadLinkIsInvalid() {
        val uri = Uri.parse("kotobaya://word/1358280")
        resolves(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(IntakeOutcome.WordPage("1358280"), start(Intent(Intent.ACTION_VIEW, uri)).model.state.value.outcome)
        val bad = start(Intent(Intent.ACTION_VIEW, Uri.parse("kotobaya://add")))
        assertEquals(IntakeOutcome.Invalid(InvalidReason.MISSING_WORD), bad.model.state.value.outcome)
        assertEquals(0, fake.requests.size)
    }
}
