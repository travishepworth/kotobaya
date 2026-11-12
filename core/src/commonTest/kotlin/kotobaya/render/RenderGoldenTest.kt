package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on jvm and js from the same common source: identical output on both targets. */
class RenderGoldenTest {
    @Test
    fun goldensCoverEveryBuiltInNoteTypeKindAndSide() {
        val expected = BuiltInNoteTypes.ALL.flatMap { t ->
            t.info.templates.flatMap { k -> listOf("front", "back").map { "${t.info.baseType.wire}/${k.kind}/$it" } }
        }.toSet()
        assertEquals(expected, RENDER_GOLDENS.keys)
        assertEquals(36, expected.size)
    }

    @Test
    fun everyBuiltInNoteTypeAndKindMatchesItsGolden() {
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            val key = RenderFixtures.key(note, kind, side)
            val golden = RENDER_GOLDENS.getValue(key)
            val actual = DefaultCardRenderer.render(RenderFixtures.input(note, kind, side))
            assertEquals(golden.html, actual.html, key)
            assertEquals(golden.audio, actual.audio, key)
            assertEquals(golden.warnings, actual.warnings, key)
            assertEquals(note.type.info.css, actual.extraCss, key)
        }
    }

    @Test
    fun goldenOutputPassesTheStrictChecker() {
        for ((note, kind, side) in RenderFixtures.goldenCases()) {
            val html = DefaultCardRenderer.render(RenderFixtures.input(note, kind, side)).html
            HtmlChecker.assertSafe(html, RenderFixtures.MEDIA)
        }
    }
}
