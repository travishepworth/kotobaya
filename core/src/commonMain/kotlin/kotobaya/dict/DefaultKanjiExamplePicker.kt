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
    private const val RENDAKU_RANK = 5

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

    /**
     * A form of step 2 with where it comes from (CORE-28 `KanjiParts.usedReading`): the stored reading
     * [source] (index [sourceIndex] in its list) and [rank], 0 for a plain form (the reading itself or a
     * kun stem), else the position of the derivation in step 2's list (1 geminated, 2 `s + o`,
     * 3 `s + masu(o)`, 4 `s + o` without `る`); the rendaku of a form ranks after every non-rendaku form.
     */
    internal class Form(
        val text: String,
        val type: KanjiReadingType,
        val source: String,
        val sourceIndex: Int,
        val rank: Int,
    )

    private fun forms(kanji: KanjiReadings, first: Boolean, last: Boolean): List<Form> {
        val out = ArrayList<Form>()
        fun add(form: String, type: KanjiReadingType, source: String, index: Int, rank: Int) {
            if (form.isEmpty()) return
            out += Form(form, type, source, index, rank)
            if (!first) for (r in rendaku(form)) out += Form(r, type, source, index, RENDAKU_RANK + rank)
        }
        for ((i, raw) in kanji.onReadings.withIndex()) {
            val r = hira(raw).replace("-", "")
            add(r, KanjiReadingType.ON, raw, i, 0)
            if (!last && r.length >= 2 && r.last() in GEMINATING) add(r.dropLast(1) + "っ", KanjiReadingType.ON, raw, i, 1)
        }
        for ((i, raw) in kanji.kunReadings.withIndex()) {
            val r = hira(raw).replace("-", "")
            val dot = r.indexOf('.')
            val stem = if (dot < 0) r else r.substring(0, dot)
            val oku = if (dot < 0) "" else r.substring(dot + 1)
            if (stem.isEmpty()) continue
            add(stem, KanjiReadingType.KUN, raw, i, 0)
            if (oku.isEmpty()) continue
            add(stem + oku, KanjiReadingType.KUN, raw, i, 2)
            val u = U_ROW.indexOf(oku.last())
            if (u >= 0) add(stem + oku.dropLast(1) + I_ROW[u], KanjiReadingType.KUN, raw, i, 3)
            if (oku.last() == 'る') add(stem + oku.dropLast(1), KanjiReadingType.KUN, raw, i, 4)
        }
        return out
    }

    /** Every form of step 2 that fits (step 3), in step 2 order; empty when the kanji is not in [headword] or [reading] is empty. */
    internal fun fittingForms(kanji: KanjiReadings, headword: String, reading: String): List<Form> {
        val h = tokens(hira(headword))
        val r = hira(reading)
        val p = h.indexOf(kanji.literal)
        if (p < 0 || r.isEmpty()) return emptyList()
        val prefix = h.subList(0, p)
        val suffix = h.subList(p + 1, h.size)
        return forms(kanji, first = p == 0, last = suffix.isEmpty()).filter { f -> fits(f.text, r, prefix, suffix) }
    }

    private fun fits(form: String, r: String, prefix: List<String>, suffix: List<String>): Boolean {
        var at = r.indexOf(form)
        while (at >= 0) {
            if (match(prefix, r.substring(0, at)) && match(suffix, r.substring(at + form.length))) return true
            at = r.indexOf(form, at + 1)
        }
        return false
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
        for (f in forms(kanji, first = p == 0, last = suffix.isEmpty())) {
            val form = f.text
            val type = f.type
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
