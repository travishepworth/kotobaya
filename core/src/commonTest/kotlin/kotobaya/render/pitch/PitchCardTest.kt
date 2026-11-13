package kotobaya.render.pitch

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.render.DefaultCardRenderer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PitchCardTest {
    private val on = RenderOptions(furigana = true, pitch = true, showMnemonic = false, mediaUrlTemplate = "/b/{sha256}")

    private fun render(reading: String, drops: List<Int>, options: RenderOptions = on, side: CardSide = CardSide.BACK): String {
        val d = DictionaryData("語", reading, emptyList(), emptyList(), drops)
        return DefaultCardRenderer.render(
            RenderInput(BuiltInNoteTypes.VOCAB.info, "pitch", emptyMap(), "", d, null, side, options, null),
        ).html
    }

    private fun m(mora: String, high: Boolean, drop: Boolean = false) =
        "<span class=\"kb-mora ${if (high) "kb-pitch-high" else "kb-pitch-low"}${if (drop) " kb-pitch-drop" else ""}\">$mora</span>"

    private fun p(name: String, vararg morae: String) =
        "<span class=\"kb-pitch\">" + morae.joinToString("") + "<small>$name</small></span>"

    @Test
    fun fourPatternTypesAreNamed() {
        assertTrue(p("heiban", m("さ", false), m("く", true), m("ら", true)) in render("さくら", listOf(0)))
        assertTrue(p("atamadaka", m("い", true, true), m("の", false), m("ち", false)) in render("いのち", listOf(1)))
        assertTrue(p("nakadaka", m("た", false), m("べ", true, true), m("る", false)) in render("たべる", listOf(2)))
        // odaka: drop on the last mora, trailing particle slot
        assertTrue(
            p("odaka", m("お", false), m("と", true), m("こ", true, true), m("○", false)) in render("おとこ", listOf(3)),
        )
    }

    @Test
    fun multiPatternKeepsGivenOrderWithSmallKana() {
        val html = render("きょう", listOf(1, 0))
        val expected = p("atamadaka", m("きょ", true, true), m("う", false)) + p("heiban", m("きょ", false), m("う", true))
        assertTrue(expected in html, html)
    }

    @Test
    fun pitchOffShowsNoMarksAnywhere() {
        val off = on.copy(pitch = false)
        for (side in CardSide.values()) {
            val html = render("きょう", listOf(1, 0), off, side)
            for (c in listOf("kb-pitch", "kb-mora", "<small>")) assertFalse(c in html, "$c in $html")
        }
    }

    @Test
    fun frontShowsKanaReadingWithoutPitch() {
        val html = render("きょう", listOf(1), side = CardSide.FRONT)
        assertTrue("kb-reading" in html && "きょう" in html)
        assertFalse("kb-pitch" in html)
    }
}
