package kotobaya.render

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SanitizerTest {
    private val hash = "d".repeat(64)

    /** Hostile values: each is tried as a field value and inside a custom template. */
    private val hostile = listOf(
        "<script>alert(1)</script>",
        "<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
        "<script>alert(1)",
        "<scr<script>ipt>alert(1)</script>",
        "<<script>script>alert(1)<</script>/script>",
        "<img src=x onerror=alert(1)>",
        "<img src=\"javascript:alert(1)\">",
        "<img src=\"data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+\">",
        "<img src=\"blob:$hash\" onerror=\"alert(1)\">",
        "<img/src=\"blob:$hash\"/onerror=alert(1)>",
        "<img src=\"blob:$hash\" alt=\"&quot; onerror=&quot;alert(1)\">",
        "<img src=\"blob:$hash\" alt='\"><script>alert(1)</script>'>",
        "<img src=\" blob:$hash\" style=\"x:expression(alert(1))\">",
        "<a href=\"javascript:alert(1)\">click</a>",
        "<a href=\"jav&#x09;ascript:alert(1)\">click</a>",
        "<svg onload=alert(1)><script>alert(1)</script></svg>",
        "<svg><p>inside svg",
        "<math><mi xlink:href=\"javascript:alert(1)\">x</mi></math>",
        "<div style=\"background:url(javascript:alert(1))\">x</div>",
        "<b onclick=\"alert(1)\">bold</b>",
        "<div\nonmouseover=alert(1)>x</div>",
        "<span class=\"kb-card x\" onclick=alert(1)>x</span>",
        "<button data-kb-audio=\"javascript:alert(1)\">x</button>",
        "<button onclick=alert(1) data-kb-audio=\"$hash\">x</button>",
        "<iframe src=\"javascript:alert(1)\"></iframe>",
        "<object data=\"javascript:alert(1)\"></object>",
        "<embed src=\"javascript:alert(1)\">",
        "<template><script>alert(1)</script></template>",
        "<style>@import 'javascript:alert(1)';</style>",
        "<!--<script>alert(1)</script>-->",
        "<!--><script>alert(1)</script>",
        "<p title=\"</p><script>alert(1)</script>\">x</p>",
        "\"><script>alert(1)</script>",
        "&lt;script&gt;alert(1)&lt;/script&gt;",
        "&#60;script&#62;alert(1)&#60;/script&#62;",
        "&#xE000;0&#xE001; &#57344;1&#57345;",
        "[sound:\" onerror=alert(1)]",
        "[sound:$hash\" onerror=alert(1)]",
        "<ruby><rt onclick=alert(1)>x</rt></ruby>",
        "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
        "<link rel=stylesheet href=javascript:alert(1)>",
        "<form action=javascript:alert(1)><input type=submit></form>",
        "<base href=\"javascript:/\">",
        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></noscript>",
        "<textarea></textarea><script>alert(1)</script>",
        "<plaintext><script>alert(1)</script>",
        "<div lang=\"en\" onfocus=alert(1) autofocus tabindex=0>x</div>",
        "<div lang=\"en&quot; onclick=&quot;x\">x</div>",
        "{{FrontSide}}{{#x}}<script>{{/x}}alert(1)",
        "<",
        "<div",
        "<img src=\"blob:$hash",
        "</div></span></ruby>",
        // C-19: a value or template must not be able to write a field region
        "<span class=\"kb-field-back\" data-field=\"back\" data-field-source=\"dictionary\">x</span>",
        "</span><span data-field=\"front\" data-field-source=\"kanji\">x",
        "<div data-field=back>x</div>",
        "0",
    )

    private fun options(furigana: Boolean, media: String = RenderFixtures.MEDIA, regions: Boolean = false) =
        RenderOptions(furigana = furigana, pitch = true, showMnemonic = true, mediaUrlTemplate = media, fieldRegions = regions)

    /** Strict allowlist check plus the C-19 region invariants, with the option off and on. */
    private fun assertSafeWithAndWithoutRegions(input: RenderInput, context: String) {
        for (regions in listOf(false, true)) {
            val card = DefaultCardRenderer.render(input.copy(options = input.options.copy(fieldRegions = regions)))
            HtmlChecker.assertSafe(card.html, RenderFixtures.MEDIA, "$context / regions=$regions")
            RegionChecker.assertConsistent(card, regions, "$context / regions=$regions")
        }
    }

    private fun customType(front: String, back: String) = BuiltInNoteTypes.CUSTOM.info.copy(
        templates = listOf(TemplateInfo("basic", true, front, back)),
    )

    private fun renderAll(fields: Map<String, String>, front: String?, back: String?, context: String) {
        for (furigana in listOf(true, false)) {
            for (side in CardSide.entries) {
                val type = if (front == null || back == null) BuiltInNoteTypes.CUSTOM.info else customType(front, back)
                val input = RenderInput(
                    noteType = type,
                    kind = "basic",
                    fields = fields,
                    mnemonic = fields["front"] ?: "",
                    dictionary = null,
                    cloze = null,
                    side = side,
                    options = options(furigana),
                )
                assertSafeWithAndWithoutRegions(input, "$context / $side / furigana=$furigana")
            }
        }
    }

    @Test
    fun hostileFieldValuesInEveryPlaceholderModeAreNeutralised() {
        val template = "{{front}}|{{furigana:front}}|{{kana:front}}|{{kanji:front}}|{{text:front}}"
        for (v in hostile) renderAll(mapOf("front" to v, "back" to v), template, "{{FrontSide}}<hr>$template", "field: $v")
    }

    @Test
    fun hostileFieldValuesInBuiltInTemplatesAreNeutralised() {
        for (v in hostile) {
            for (type in BuiltInNoteTypes.ALL) {
                for (t in type.info.templates) {
                    for (side in CardSide.entries) {
                        val input = RenderInput(
                            noteType = type.info,
                            kind = t.kind,
                            fields = type.info.fieldKeys.associateWith { v },
                            mnemonic = v,
                            dictionary = null,
                            cloze = RenderFixtures.CLOZE.copy(text = v + "abcdefgh", translation = v),
                            side = side,
                            options = options(true),
                        )
                        assertSafeWithAndWithoutRegions(input, "${type.name}/${t.kind}: $v")
                    }
                }
            }
        }
    }

    @Test
    fun hostileCustomTemplatesAreNeutralised() {
        for (v in hostile) renderAll(mapOf("front" to "f", "back" to "b"), "$v{{front}}$v", "{{FrontSide}}$v{{back}}", "template: $v")
    }

    @Test
    fun hostilePiecesInsideTemplateAttributesAreNeutralised() {
        val front = "<img src=\"{{front}}\" alt=\"{{front}}\"><span class=\"{{front}}\" lang=\"{{front}}\">x</span>"
        for (v in hostile) renderAll(mapOf("front" to v), front, "{{FrontSide}}", "attribute: $v")
    }

    @Test
    fun randomConcatenationsOfHostileFragmentsStaySafe() {
        val fragments = hostile + listOf("<", ">", "\"", "'", "=", "/", " ", "on", "script", "svg", "img", "&", "#", ";", "[", "]", "{{", "}}", "<b>", "</b>", "<ruby>", "<rt>", "</rt>")
        val random = Random(20261113)
        repeat(400) { n ->
            val v = buildString { repeat(random.nextInt(1, 8)) { append(fragments[random.nextInt(fragments.size)]) } }
            val t = buildString { repeat(random.nextInt(1, 5)) { append(fragments[random.nextInt(fragments.size)]) } }
            renderAll(mapOf("front" to v, "back" to v), "$t{{front}}", "{{FrontSide}}$t{{back}}", "random #$n: $v / $t")
        }
    }

    @Test
    fun allowedMarkupIsKeptAndCanonicalised() {
        val out = DefaultCardRenderer.render(
            RenderInput(
                noteType = customType("{{front}}", "{{back}}"),
                kind = "basic",
                fields = mapOf(
                    "front" to "<B CLASS='user-x kb-meaning evil'>b</B><img alt=\"pic\" src=\"blob:${hash.uppercase()}\"><br><span lang=ja>日本</span>",
                ),
                mnemonic = "",
                dictionary = null,
                cloze = null,
                side = CardSide.FRONT,
                options = options(true),
            ),
        )
        assertEquals(
            "<div class=\"kb-card kb-custom kb-kind-basic kb-front\"><span class=\"kb-field-front\">" +
                "<b class=\"user-x kb-meaning\">b</b><img src=\"/api/v1/blobs/$hash\" alt=\"pic\"><br><span lang=\"ja\">日本</span></span></div>",
            out.html,
        )
    }

    @Test
    fun removedElementsLoseTheirContentAndOthersAreUnwrapped() {
        val html = render("<svg><text>secret</text></svg><script>code</script><a href=\"x\">link</a><font>kept</font>")
        assertEquals("<span class=\"kb-field-front\">linkkept</span>", html)
    }

    @Test
    fun imgWithANonBlobSourceIsDropped() {
        assertEquals("<span class=\"kb-field-front\">ab</span>", render("a<img src=\"https://x/y.png\">b"))
        assertEquals("<span class=\"kb-field-front\">ab</span>", render("a<img src=\"blob:abc\">b"))
    }

    @Test
    fun soundTagsBecomeAudioButtons() {
        val out = DefaultCardRenderer.render(input("x[sound:$hash]y[sound:${"e".repeat(64)}][sound:$hash]"))
        assertEquals(
            "<span class=\"kb-field-front\">x<button class=\"kb-audio\" data-kb-audio=\"$hash\"></button>y" +
                "<button class=\"kb-audio\" data-kb-audio=\"${"e".repeat(64)}\"></button>" +
                "<button class=\"kb-audio\" data-kb-audio=\"$hash\"></button></span>",
            inner(out.html),
        )
        assertEquals(listOf(hash, "e".repeat(64)), out.audio)
    }

    @Test
    fun droppedMarkupIsReportedAsWarnings() {
        val out = DefaultCardRenderer.render(input("<script>x</script><b onclick=x>y</b>"))
        assertTrue("render.warning.dropped_tag" in out.warnings)
        assertTrue("render.warning.dropped_attribute" in out.warnings)
    }

    @Test
    fun mediaUrlTemplateIsAttributeEscaped() {
        val input = input("<img src=\"blob:$hash\">").copy(options = options(true, "/m?a=1&b=\"{sha256}\""))
        assertEquals(
            "<span class=\"kb-field-front\"><img src=\"/m?a=1&amp;b=&quot;$hash&quot;\"></span>",
            inner(DefaultCardRenderer.render(input).html),
        )
    }

    private fun input(front: String) = RenderInput(
        noteType = BuiltInNoteTypes.CUSTOM.info,
        kind = "basic",
        fields = mapOf("front" to front),
        mnemonic = "",
        dictionary = null,
        cloze = null,
        side = CardSide.FRONT,
        options = options(true),
    )

    private fun render(front: String): String = inner(DefaultCardRenderer.render(input(front)).html)

    private fun inner(html: String): String = html.substringAfter('>').removeSuffix("</div>")
}
