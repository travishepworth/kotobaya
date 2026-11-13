package kotobaya.android.notes

import java.text.Normalizer
import kotobaya.android.dict.InstalledDictionary
import kotobaya.contracts.render.DictionaryData

/** One dictionary entry as the matcher sees it (all kanji forms and readings, not only the headword). */
data class EntryCandidate(
    val seq: Long,
    val headword: String,
    val headwordReading: String,
    val kanjiForms: List<String>,
    val readings: List<String>,
    val glosses: List<String>,
    val isCommon: Boolean,
    val freqRank: Int?,
    val pitchDrops: List<Int> = emptyList(),
) {
    /** What the renderer fills empty fields from (and what the front-empty check sees). */
    fun dictionaryData(): DictionaryData = DictionaryData(headword, headwordReading, emptyList(), glosses, pitchDrops)
}

/** The local dictionary as the matcher reads it. */
interface CandidateSource {
    /** False when no dictionary is installed (every match is then [MatchResult.NoMatch]). */
    fun isAvailable(): Boolean

    /**
     * Every live (not retired) entry that has [text] as a kanji form or a reading (kana compared with
     * katakana folded to hiragana). Order does not matter; the matcher sorts.
     */
    fun candidates(text: String): List<EntryCandidate>
}

/** How a request matched (SPEC "Takoboto integration": matched to a JMdict entry locally). */
sealed interface MatchResult {
    /**
     * Matched entry [entry] in [tier]. [alternatives] are the other candidates of that tier (sorted
     * like the winner was picked), empty when the match was unique.
     */
    data class Matched(val entry: EntryCandidate, val tier: MatchTier, val alternatives: List<Long>) : MatchResult {
        val ambiguous: Boolean get() = alternatives.isNotEmpty()
    }

    /** No entry (or no dictionary installed): the note is created without `entry_seq`, flagged for later matching. */
    data object NoMatch : MatchResult
}

enum class MatchTier {
    /** The word is a form of the entry and the reading is one of its readings. */
    WORD_AND_READING,

    /** The word is a form of the entry (no reading given, or none matched with it). */
    WORD,

    /** Only the reading matched (no usable word given). */
    READING,
}

/**
 * Matches a (word, reading) pair to one dictionary entry, deterministically:
 *
 * 1. [MatchTier.WORD_AND_READING]: the word is one of the entry's kanji forms or readings and the
 *    reading is one of its readings.
 * 2. [MatchTier.WORD]: the word alone is one of its forms.
 * 3. [MatchTier.READING]: the reading alone is one of its readings — **only** when the word is absent or
 *    contains no kanji. A kanji word whose forms are not in the dictionary is NOT matched by its reading:
 *    that would pick a homophone (橋/箸), and a wrong `entry_seq` makes the server merge the note into an
 *    unrelated word (SYNC.md 8.1). It stays unmatched instead.
 *
 * Ties inside a tier, in order: the word (else the reading) is the headword, the reading is the headword
 * reading, common first, lower frequency rank first (none last), lower `seq` first. The last key is unique,
 * so the result never depends on the source's order.
 */
class EntryMatcher(private val source: CandidateSource) {
    fun match(word: String?, reading: String?): MatchResult {
        val w = clean(word)
        val r = clean(reading)
        if ((w == null && r == null) || !source.isAvailable()) return MatchResult.NoMatch
        val byWord = w?.let { source.candidates(it).filter { c -> c.hasForm(w) } }.orEmpty()
        if (w != null && r != null) {
            pick(byWord.filter { it.hasReading(r) }, MatchTier.WORD_AND_READING, w, r)?.let { return it }
        }
        if (w != null) pick(byWord, MatchTier.WORD, w, r)?.let { return it }
        if (r != null && (w == null || !containsKanji(w))) {
            pick(source.candidates(r).filter { it.hasReading(r) }, MatchTier.READING, w ?: r, r)?.let { return it }
        }
        return MatchResult.NoMatch
    }

    private fun pick(found: List<EntryCandidate>, tier: MatchTier, word: String, reading: String?): MatchResult.Matched? {
        if (found.isEmpty()) return null
        val sorted = found.distinctBy { it.seq }.sortedWith(
            compareBy<EntryCandidate> { if (it.headword == word) 0 else 1 }
                .thenBy { if (reading != null && fold(it.headwordReading) == fold(reading)) 0 else 1 }
                .thenBy { if (it.isCommon) 0 else 1 }
                .thenBy { it.freqRank ?: Int.MAX_VALUE }
                .thenBy { it.seq },
        )
        return MatchResult.Matched(sorted.first(), tier, sorted.drop(1).map { it.seq })
    }

    companion object {
        /** NFKC, trimmed; null when empty. */
        fun clean(text: String?): String? = text?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }?.takeIf { it.isNotEmpty() }

        /** Katakana folded to hiragana (the dictionary's `norm` folding for kana). */
        fun fold(text: String): String = buildString(text.length) {
            for (ch in text) {
                val cp = ch.code
                append(if (cp in 0x30A1..0x30F6 || cp == 0x30FD || cp == 0x30FE) (cp - 0x60).toChar() else ch)
            }
        }

        fun containsKanji(text: String): Boolean = text.codePoints().anyMatch {
            it in 0x3400..0x4DBF || it in 0x4E00..0x9FFF || it in 0xF900..0xFAFF || it in 0x20000..0x323AF
        }

        private fun EntryCandidate.hasForm(text: String): Boolean = text in kanjiForms || hasReading(text)

        private fun EntryCandidate.hasReading(text: String): Boolean = fold(text).let { f -> readings.any { fold(it) == f } }
    }
}

/**
 * [CandidateSource] over the installed dictionary file: the exact-form hits of [InstalledDictionary.search]
 * (grade 0 sorts first, so paging stops at the first non-exact hit) expanded with [InstalledDictionary.entry].
 */
class InstalledCandidateSource(private val dict: InstalledDictionary) : CandidateSource {
    override fun isAvailable(): Boolean = dict.isAvailable()

    override fun candidates(text: String): List<EntryCandidate> {
        val key = EntryMatcher.fold(text)
        val out = ArrayList<EntryCandidate>()
        var cursor: kotobaya.android.dict.DictSearchCursor? = null
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val page = dict.search(text, limit = PAGE, after = cursor)
            var stop = false
            for (hit in page.items) {
                val c = dict.entry(hit.seq)?.let(::toCandidate) ?: continue
                val exact = c.kanjiForms.any { EntryMatcher.fold(it) == key } || c.readings.any { EntryMatcher.fold(it) == key }
                if (exact) out += c else stop = true
            }
            cursor = page.next ?: break
            if (stop) break
        }
        return out
    }

    private fun toCandidate(d: kotobaya.android.dict.DictEntryDetail): EntryCandidate? {
        if (d.retired) return null
        fun texts(a: org.json.JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("text")?.takeIf(String::isNotEmpty) }
        val glosses = d.senses.optJSONObject(0)?.optJSONArray("gloss")?.let { gl ->
            (0 until gl.length()).mapNotNull { gl.optJSONObject(it)?.optString("text") }
        }.orEmpty()
        val pitch = d.pitch.firstOrNull { it.reading == d.headwordReading }?.positions.orEmpty()
        return EntryCandidate(
            d.seq, d.headword, d.headwordReading, texts(d.kanjiForms), texts(d.readings), glosses, d.isCommon, d.freqRank, pitch,
        )
    }

    private companion object {
        const val PAGE = 50
        const val MAX_PAGES = 10
    }
}
