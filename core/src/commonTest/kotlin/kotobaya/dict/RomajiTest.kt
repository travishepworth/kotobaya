package kotobaya.dict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RomajiTest {
    @Test
    fun parityWithServerCases() {
        val cases = mapOf(
            "taberu" to "たべる", "kanji" to "かんじ", "konnichiwa" to "こんにちわ", "sensei" to "せんせい",
            "gakkou" to "がっこう", "kitte" to "きって", "matcha" to "まっちゃ", "shinbun" to "しんぶん",
            "nn" to "ん", "kan'i" to "かんい", "kannai" to "かんない", "ja" to "じゃ", "kyou" to "きょう",
            "tsunami" to "つなみ", "fuji" to "ふじ", "chotto" to "ちょっと", "ra-men" to "らーめん",
            "TOUKYOU" to "とうきょう", "wo" to "を", "n" to "ん", "shi" to "し", "ji" to "じ", "kannna" to "かんな",
        )
        for ((r, k) in cases) assertEquals(k, Romaji.toHiragana(r), r)
        assertNull(Romaji.toHiragana("teacher!x"))
        assertNull(Romaji.toHiragana("l"))
    }
}
