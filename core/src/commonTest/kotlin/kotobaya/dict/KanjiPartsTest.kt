package kotobaya.dict

import kotobaya.contracts.dict.KanjiPart
import kotobaya.contracts.dict.KanjiPartSource
import kotobaya.contracts.dict.KanjiParts
import kotobaya.contracts.dict.KanjiReadingType
import kotobaya.contracts.dict.KanjiReadingType.KUN
import kotobaya.contracts.dict.KanjiReadingType.ON
import kotobaya.contracts.dict.KanjiReadings
import kotobaya.contracts.dict.UsedReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ADR-041: `KanjiParts.usedReading` examples, its agreement with `classify`, and `build`. Readings are KANJIDIC's. */
class KanjiPartsTest {
    private val parts: KanjiParts = DefaultKanjiParts

    private fun kanji(literal: String, on: String, kun: String) =
        KanjiReadings(literal, on.split(' ').filter { it.isNotEmpty() }, kun.split(' ').filter { it.isNotEmpty() })

    private val gaku = kanji("学", "ガク", "まな.ぶ")
    private val kou = kanji("校", "コウ キョウ", "")
    private val dai = kanji("大", "ダイ タイ", "おお- おお.きい -おお.いに")
    private val shoku = kanji("食", "ショク ジキ", "く.う く.らう た.べる は.む")
    private val nichi = kanji("日", "ニチ ジツ", "ひ -び -か")
    private val ima = kanji("今", "コン キン", "いま")

    @Test
    fun usedReadingExamples() {
        assertEquals(UsedReading(ON, "ガク", "がっ"), parts.usedReading(gaku, "学校", "がっこう"))
        assertEquals(UsedReading(ON, "コウ", "こう"), parts.usedReading(kou, "学校", "がっこう"))
        assertEquals(UsedReading(ON, "ダイ", "だい"), parts.usedReading(dai, "大学", "だいがく"))
        assertEquals(UsedReading(ON, "ガク", "がく"), parts.usedReading(gaku, "大学", "だいがく"))
        assertEquals(UsedReading(KUN, "た.べる", "た"), parts.usedReading(shoku, "食べる", "たべる"))
        // The plain `-び` beats the rendaku of `ひ`.
        assertEquals(UsedReading(KUN, "-び", "び"), parts.usedReading(nichi, "誕生日", "たんじょうび"))
        assertNull(parts.usedReading(ima, "今日", "きょう"))
        assertNull(parts.usedReading(nichi, "今日", "きょう"))
        assertNull(parts.usedReading(nichi, "日本", "にほん"))
        assertNull(parts.usedReading(KanjiReadings("学", emptyList(), emptyList()), "学校", "がっこう"))
        assertNull(parts.usedReading(gaku, "学校", ""))
        assertNull(parts.usedReading(gaku, "大人", "おとな"))
    }

    @Test
    fun usedReadingAgreesWithClassifyOnTheC25Goldens() {
        for ((k, cases) in KanjiExamplePickerTest().goldens) {
            for ((headword, reading, _) in cases) {
                val type = DefaultKanjiExamplePicker.classify(k, headword, reading)
                val used = parts.usedReading(k, headword, reading)
                if (type == KanjiReadingType.OTHER) {
                    assertNull(used, "${k.literal} in $headword")
                } else {
                    assertEquals(type, used?.type, "${k.literal} in $headword")
                    val list = if (type == ON) k.onReadings else k.kunReadings
                    assertEquals(true, used?.reading in list, "${k.literal} in $headword: stored reading")
                }
            }
        }
        assertEquals(15, KanjiExamplePickerTest().goldens.size)
    }

    @Test
    fun buildOrderDuplicatesKeywordAndMissingData() {
        val sources = listOf(
            KanjiPartSource(kou, listOf("exam", "school", "printing"), null),
            KanjiPartSource(gaku, listOf("study", "learning", "science"), "  "),
        )
        assertEquals(
            listOf(
                KanjiPart("学", "study", listOf("study", "learning", "science"), listOf("ガク"), listOf("まな.ぶ"), UsedReading(ON, "ガク", "がっ")),
                KanjiPart("校", "exam", listOf("exam", "school", "printing"), listOf("コウ", "キョウ"), emptyList(), UsedReading(ON, "コウ", "こう")),
            ),
            parts.build("学校", "がっこう", sources),
        )
        // The user's keyword comes before the first meaning.
        val mine = parts.build("学", "がく", listOf(KanjiPartSource(gaku, listOf("study"), "learn")))
        assertEquals("learn", mine.single().keyword)
        // The same kanji twice: one tile, aligned on its first occurrence (日曜日 にちようび → ニチ).
        val youbi = parts.build("日曜日", "にちようび", listOf(KanjiPartSource(nichi, listOf("day"), null)))
        assertEquals(listOf("日", "曜"), youbi.map { it.literal })
        assertEquals(UsedReading(ON, "ニチ", "にち"), youbi[0].usedReading)
        // A kanji not in the dictionary: the literal alone.
        assertEquals(KanjiPart("曜", null, emptyList(), emptyList(), emptyList(), null), youbi[1])
        // Kana-only or empty expression: no tiles; an empty reading: no alignment.
        assertEquals(emptyList(), parts.build("たべる", "たべる", emptyList()))
        assertEquals(emptyList(), parts.build("", "", emptyList()))
        assertNull(parts.build("学校", "", listOf(KanjiPartSource(gaku, emptyList(), null)))[0].usedReading)
        // At most MAX_PARTS tiles.
        val long = "一二三四五六七八九十"
        assertEquals(KanjiParts.MAX_PARTS, parts.build(long, "", emptyList()).size)
        assertEquals(long.take(8).map { it.toString() }, parts.build(long, "", emptyList()).map { it.literal })
    }

    @Test
    fun threeRealWords() {
        // Ticket: 大学, 今日 (null, irregular), 食べる.
        val daigaku = parts.build("大学", "だいがく", listOf(KanjiPartSource(dai, listOf("large"), null), KanjiPartSource(gaku, listOf("study"), null)))
        assertEquals(listOf(UsedReading(ON, "ダイ", "だい"), UsedReading(ON, "ガク", "がく")), daigaku.map { it.usedReading })
        val kyou = parts.build("今日", "きょう", listOf(KanjiPartSource(ima, listOf("now"), null), KanjiPartSource(nichi, listOf("day"), null)))
        assertEquals(listOf(null, null), kyou.map { it.usedReading })
        assertEquals(listOf("now", "day"), kyou.map { it.keyword })
        val taberu = parts.build("食べる", "たべる", listOf(KanjiPartSource(shoku, listOf("eat", "food"), null)))
        assertEquals(UsedReading(KUN, "た.べる", "た"), taberu.single().usedReading)
    }
}
