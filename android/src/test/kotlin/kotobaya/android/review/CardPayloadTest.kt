package kotobaya.android.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.contracts.render.RenderedCard

/**
 * AND-CARDHTML: the component that builds the WebView payload never hands WebView an empty or blank document, and
 * WebView's own document request (`loadDataWithBaseURL`) is never answered with a blocked response.
 */
class CardPayloadTest {
    private val logs = mutableListOf<String>()
    private val reports = mutableListOf<String>()
    private val payloads = CardPayloads(CardFailures(log = { logs += it }, report = { reports += it }, maxReports = 3))

    private fun card(html: String) = RenderedCard(html, emptyList(), "", emptyList())

    private fun front(inner: String) = card("""<div class="kb-card kb-vocab kb-kind-meaning kb-front">$inner</div>""")

    @Test
    fun aCardWithTextIsAFullThemedPage() {
        val page = assertIs<CardPayload.Page>(payloads.of(front("""<div class="kb-expression">語</div>"""), dark = true))
        assertTrue(page.html.startsWith("<!doctype html><html data-theme=\"dark\">"), page.html)
        assertTrue("語" in page.html && "card.css" in page.html)
        assertEquals(emptyList(), reports)
        assertEquals(emptyList(), logs)
    }

    @Test
    fun emptyAndBlankRendersAreFailuresLoggedAndReported() {
        assertEquals(CardPayload.Failed("empty render unknown card"), payloads.of(card(""), dark = false))
        for (inner in listOf("", """<div class="kb-expression"></div>""", "<div> &nbsp; 　</div>", "<hr>")) {
            assertEquals(CardPayload.Failed("blank render vocab/meaning front"), payloads.of(front(inner), dark = false), inner)
        }
        assertEquals(5, logs.size, "every failure is logged")
        assertEquals(listOf("empty render unknown card", "blank render vocab/meaning front"), reports, "one report per distinct reason")
    }

    @Test
    fun imagesAndAudioCountAsContent() {
        assertIs<CardPayload.Page>(payloads.of(front("""<img src="https://${CardPage.HOST}/media/${"a".repeat(64)}">"""), false))
        assertIs<CardPayload.Page>(payloads.of(front("""<button class="kb-audio" data-kb-audio="${"b".repeat(64)}"></button>"""), false))
        assertFalse(CardPayloads.hasVisibleContent("<div><span></span></div>"))
    }

    @Test
    fun reportsAreRateLimitedPerProcess() {
        repeat(10) { payloads.loadFailed("http $it") }
        assertEquals(10, logs.size)
        assertEquals(listOf("card page load failed: http 0", "card page load failed: http 1", "card page load failed: http 2"), reports)
    }

    @Test
    fun aReportOrLogThatThrowsDoesNotBreakTheScreen() {
        val p = CardPayloads(CardFailures(log = { error("log") }, report = { error("report") }))
        assertIs<CardPayload.Failed>(p.of(card(""), dark = false))
    }

    /** The live 0.1.11 symptom: WebView asks for the document of loadDataWithBaseURL as this URL; it must pass. */
    @Test
    fun theDocumentOfLoadDataWithBaseUrlIsLeftToWebView() {
        val doc = "data:text/html;charset=utf-8;base64,"
        assertEquals(PageRequest.Document, CardPage.decide(doc, mainFrame = true))
        assertEquals(PageRequest.Document, CardPage.decide("DATA:text/html,x", mainFrame = true))
        // only the main frame: a sub-resource data: URL and any other main-frame URL stay blocked
        assertEquals(PageRequest.Blocked, CardPage.decide(doc, mainFrame = false))
        assertEquals(PageRequest.Blocked, CardPage.decide("https://example.com/", mainFrame = true))
        assertEquals(PageRequest.Asset("card/card.css"), CardPage.decide("https://${CardPage.HOST}/assets/card/card.css", mainFrame = false))
    }
}
