package kotobaya.dict

import kotobaya.contracts.WordStatus
import kotobaya.contracts.dict.KanjiExample
import kotobaya.contracts.dict.KanjiExampleCandidate
import kotobaya.contracts.dict.KanjiExamplePicker
import kotobaya.contracts.dict.KanjiReadingType
import kotobaya.contracts.dict.KanjiReadings

/** The [KanjiExamplePicker] of every platform (C-25). Stateless; the rules are the interface's KDoc. */
public object DefaultKanjiExamplePicker : KanjiExamplePicker {
    private const val NO_START = "ぁぃぅぇぉっゃゅょゎんー"
    private const val GEMINATING = "きくちつ"
    private const val U_ROW = "うくぐすつぬぶむる"
    private const val I_ROW = "いきぎしちにびみり"
    private const val UNVOICED = "かきくけこさしすせそたちつてとはひふへほ"
    private const val VOICED = "がぎぐげござじずぜぞだぢづでどばびぶべぼ"
    private const val H_ROW = "はひふへほ"
    private const val P_ROW = "ぱぴぷぺぽ"

    /** The code points of [s], each as a string. */
    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>(s.length)
        var i = 0
        while (i < s.length) {
            val pair = s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()
            out += if (pair) s.substring(i, i + 2) else s[i].toString()
            i += if (pair) 2 else 1
        }
        return out
    }

    private fun hira(s: String): String {
        if (s.none { it in 'ァ'..'ヶ' }) return s
        return buildString(s.length) { for (ch in s) append(if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch) }
    }

    private fun isKana(token: String): Boolean = token.length == 1 && (token[0] in 'ぁ'..'ゖ' || token[0] == 'ー')

    /** `match(tokens, text)` of the contract, from token [t] and text offset [i]. */
    private fun match(tokens: List<String>, text: String, t: Int = 0, i: Int = 0): Boolean {
        if (t == tokens.size) return i == text.length
        if (isKana(tokens[t])) return i < text.length && text[i] == tokens[t][0] && match(tokens, text, t + 1, i + 1)
        var end = t
        while (end < tokens.size && !isKana(tokens[end])) end++
        val m = end - t
        if (i >= text.length || text[i] in NO_START) return false
        for (k in m..(text.length - i)) if (match(tokens, text, end, i + k)) return true
        return false
    }

    private fun rendaku(form: String): List<String> {
        val first = form[0]
        val rest = form.substring(1)
        val out = ArrayList<String>(2)
        val v = UNVOICED.indexOf(first)
        if (v >= 0) out += VOICED[v] + rest
        val p = H_ROW.indexOf(first)
        if (p >= 0) out += P_ROW[p] + rest
        return out
    }

    private fun forms(kanji: KanjiReadings, first: Boolean, last: Boolean): List<Pair<String, KanjiReadingType>> {
        val out = ArrayList<Pair<String, KanjiReadingType>>()
        fun add(form: String, type: KanjiReadingType) {
            if (form.isEmpty()) return
            out += form to type
            if (!first) for (r in rendaku(form)) out += r to type
        }
        for (raw in kanji.onReadings) {
            val r = hira(raw).replace("-", "")
            add(r, KanjiReadingType.ON)
            if (!last && r.length >= 2 && r.last() in GEMINATING) add(r.dropLast(1) + "っ", KanjiReadingType.ON)
        }
        for (raw in kanji.kunReadings) {
            val r = hira(raw).replace("-", "")
            val dot = r.indexOf('.')
            val stem = if (dot < 0) r else r.substring(0, dot)
            val oku = if (dot < 0) "" else r.substring(dot + 1)
            if (stem.isEmpty()) continue
            add(stem, KanjiReadingType.KUN)
            if (oku.isEmpty()) continue
            add(stem + oku, KanjiReadingType.KUN)
            val u = U_ROW.indexOf(oku.last())
            if (u >= 0) add(stem + oku.dropLast(1) + I_ROW[u], KanjiReadingType.KUN)
            if (oku.last() == 'る') add(stem + oku.dropLast(1), KanjiReadingType.KUN)
        }
        return out
    }

    override fun classify(kanji: KanjiReadings, headword: String, reading: String): KanjiReadingType {
        val h = tokens(hira(headword))
        val r = hira(reading)
        val p = h.indexOf(kanji.literal)
        if (p < 0 || r.isEmpty()) return KanjiReadingType.OTHER
        val prefix = h.subList(0, p)
        val suffix = h.subList(p + 1, h.size)
        var best: KanjiReadingType = KanjiReadingType.OTHER
        var bestLength = 0
        for ((form, type) in forms(kanji, first = p == 0, last = suffix.isEmpty())) {
            // Only a longer form, or an ON form as long as the best KUN form, can change the result.
            val better = form.length > bestLength || (form.length == bestLength && type == KanjiReadingType.ON && best == KanjiReadingType.KUN)
            if (!better) continue
            var at = r.indexOf(form)
            while (at >= 0) {
                if (match(prefix, r.substring(0, at)) && match(suffix, r.substring(at + form.length))) {
                    best = type
                    bestLength = form.length
                    break
                }
                at = r.indexOf(form, at + 1)
            }
        }
        return best
    }

    private fun mine(c: KanjiExampleCandidate): Boolean =
        c.hasNote || c.inDecks || c.status == WordStatus.LEARNING || c.status == WordStatus.KNOWN || c.status == WordStatus.NEVER_FORGET

    private val pickOrder: Comparator<KanjiExampleCandidate> =
        compareBy<KanjiExampleCandidate> { if (mine(it)) 0 else 1 }
            .thenBy { if (it.freqRank == null) 1 else 0 }
            .thenBy { it.freqRank ?: 0 }
            .thenBy { it.seq }

    override fun pick(kanji: KanjiReadings, candidates: List<KanjiExampleCandidate>, limit: Int): List<KanjiExample> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<Long>()
        val window = candidates
            .filter { it.status != WordStatus.BLACKLISTED && it.status != WordStatus.IGNORED && it.headword.contains(kanji.literal) && seen.add(it.seq) }
            .sortedWith(pickOrder)
            .take(KanjiExamplePicker.CANDIDATE_LIMIT)
            .map { KanjiExample(it, classify(kanji, it.headword, it.reading)) }
        val taken = BooleanArray(window.size)
        var count = 0
        fun take(i: Int) {
            if (i >= 0 && !taken[i] && count < limit) {
                taken[i] = true
                count++
            }
        }
        take(window.indexOfFirst { it.readingType == KanjiReadingType.ON })
        take(window.indexOfFirst { it.readingType == KanjiReadingType.KUN })
        for (i in window.indices) take(i)
        return window.filterIndexed { i, _ -> taken[i] }
    }
}
