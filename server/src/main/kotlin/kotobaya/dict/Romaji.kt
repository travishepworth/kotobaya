package kotobaya.dict

/**
 * Table-driven romaji to hiragana conversion (wanakana-equivalent: Hepburn and common IME spellings,
 * `nn` / `n'` for ん, a doubled consonant for っ, `-` for ー).
 *
 * T106: the OpenAPI text says "the core's romaji-to-kana conversion", but core has none; this local
 * copy should move to core (shared with web search input) when a ticket covers it.
 */
object Romaji {
    private val vowels = "aiueo"
    private val kana = "あいうえお"
    private val smallKana = "ぁぃぅぇぉ"

    private val table: Map<String, String> = buildMap {
        for ((i, v) in vowels.withIndex()) {
            put(v.toString(), kana[i].toString())
            put("x$v", smallKana[i].toString())
            put("l$v", smallKana[i].toString())
        }
        // Rows of plain syllables: consonant to kana for a i u e o.
        val rows = mapOf(
            "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの",
            "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご",
            "z" to "ざじずぜぞ", "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ",
        )
        for ((c, ks) in rows) {
            for (i in 0 until 5) put(c + vowels[i], ks[i].toString())
        }
        putAll(
            mapOf(
                "fa" to "ふぁ", "fi" to "ふぃ", "fu" to "ふ", "fe" to "ふぇ", "fo" to "ふぉ",
                "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
                "ja" to "じゃ", "ji" to "じ", "ju" to "じゅ", "je" to "じぇ", "jo" to "じょ",
                "qa" to "くぁ", "qi" to "くぃ", "qu" to "く", "qe" to "くぇ", "qo" to "くぉ",
                "wa" to "わ", "wi" to "ゐ", "wu" to "う", "we" to "ゑ", "wo" to "を",
                "ya" to "や", "yu" to "ゆ", "yo" to "よ", "ye" to "いぇ", "yi" to "い",
                "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ", "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
                "xwa" to "ゎ", "lwa" to "ゎ", "xtsu" to "っ", "xtu" to "っ", "ltsu" to "っ", "ltu" to "っ",
                "xka" to "ゕ", "xke" to "ゖ",
                "si" to "し", "shi" to "し", "ti" to "ち", "chi" to "ち", "tu" to "つ", "tsu" to "つ", "hu" to "ふ",
                "zi" to "じ", "di" to "ぢ", "du" to "づ", "ci" to "し", "cu" to "く", "ce" to "せ", "ca" to "か", "co" to "こ",
                "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "she" to "しぇ",
                "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "che" to "ちぇ",
                "tsa" to "つぁ", "tsi" to "つぃ", "tse" to "つぇ", "tso" to "つぉ",
                "thi" to "てぃ", "thu" to "てゅ", "dhi" to "でぃ", "dhu" to "でゅ",
                "twu" to "とぅ", "dwu" to "どぅ", "wha" to "うぁ", "whi" to "うぃ", "whe" to "うぇ", "who" to "うぉ",
                "fyu" to "ふゅ", "vyu" to "ゔゅ",
            ),
        )
        // Digraphs: consonant + y + a/u/o.
        val ya = mapOf(
            "ky" to "き", "gy" to "ぎ", "sy" to "し", "zy" to "じ", "jy" to "じ", "ty" to "ち", "cy" to "ち",
            "dy" to "ぢ", "ny" to "に", "hy" to "ひ", "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り",
        )
        for ((c, k) in ya) {
            put(c + "a", k + "ゃ")
            put(c + "u", k + "ゅ")
            put(c + "o", k + "ょ")
            put(c + "e", k + "ぇ")
        }
        put("n", "ん")
    }

    private val maxKey = table.keys.maxOf { it.length }

    /**
     * The whole of [input] as hiragana, or null when any Latin letter cannot be converted.
     * Case-insensitive; non-letters other than `-` (ー) and `'` after n pass through unchanged.
     */
    fun toHiragana(input: String): String? {
        val s = input.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == 'n') {
                val next = s.getOrNull(i + 1)
                val syllable = longest(s, i)
                // "n" alone is in the table; anything longer starting with n is a real syllable (na, nya).
                if (syllable != null && syllable.first.length > 1) {
                    out.append(syllable.second)
                    i += syllable.first.length
                } else if (next == '\'' ) {
                    out.append('ん')
                    i += 2
                } else if (next == 'n') {
                    val after = s.getOrNull(i + 2)
                    out.append('ん')
                    // "nn" is one ん unless a vowel or y follows ("konnichiwa" = こ ん にちわ).
                    i += if (after != null && (after in vowels || after == 'y')) 1 else 2
                } else {
                    out.append('ん')
                    i += 1
                }
                continue
            }
            if (c == '-') {
                out.append('ー')
                i++
                continue
            }
            if (c !in 'a'..'z') {
                out.append(input[i]) // lowercase() can change length only for exotic chars; keep the original
                i++
                continue
            }
            // Doubled consonant (not vowel, not n) becomes っ: "kko", "tchi".
            val next = s.getOrNull(i + 1)
            if (c !in vowels && c != 'y' && next == c) {
                out.append('っ')
                i++
                continue
            }
            if (c == 't' && next == 'c' && s.getOrNull(i + 2) == 'h') {
                out.append('っ')
                i++
                continue
            }
            val m = longest(s, i) ?: return null
            out.append(m.second)
            i += m.first.length
        }
        return out.toString()
    }

    private fun longest(s: String, at: Int): Pair<String, String>? {
        for (len in minOf(maxKey, s.length - at) downTo 1) {
            val key = s.substring(at, at + len)
            table[key]?.let { return key to it }
        }
        return null
    }
}
