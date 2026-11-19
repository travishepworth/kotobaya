package kotobaya.android.queue

import kotobaya.android.dict.InstalledDictionary
import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.WordForm
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/** What [KanjiSource] reads from the dictionary: batched, narrow reads (L-7 steps 1-2, never the network). */
interface KanjiDictionary {
    /** Changes whenever the package or the sidecar cache changes; cached answers (hits and misses) are valid for one value. */
    val generation: Long

    /** `kanji_forms` and `senses` JSON of each entry found; an absent key is a miss. */
    fun formJson(seqs: Collection<Long>): Map<Long, InstalledDictionary.FormJson>

    /** (grade, frequency rank) of each kanji found; an absent key is a miss. */
    fun kanjiFacts(literals: Collection<String>): Map<String, Pair<Int?, Int?>>

    companion object {
        val NONE: KanjiDictionary = object : KanjiDictionary {
            override val generation: Long = 0
            override fun formJson(seqs: Collection<Long>) = emptyMap<Long, InstalledDictionary.FormJson>()
            override fun kanjiFacts(literals: Collection<String>) = emptyMap<String, Pair<Int?, Int?>>()
        }

        fun of(dict: InstalledDictionary): KanjiDictionary = object : KanjiDictionary {
            override val generation: Long get() = dict.generation
            override fun formJson(seqs: Collection<Long>) = runCatching { dict.formJson(seqs) }.getOrDefault(emptyMap())
            override fun kanjiFacts(literals: Collection<String>) = runCatching { dict.kanjiFacts(literals) }.getOrDefault(emptyMap())
        }
    }
}

/**
 * What core `KanjiRequirement` needs from the on-device dictionary (contracts/core README "C-27"; SHELL.md 4.3, 10.7):
 * the form tags and `uk` of an entry's displayed form ([WordForm]) and the grade / frequency rank of a kanji
 * ([KanjiFacts]). A miss gives no tags / no facts, exactly the documented difference of SHELL.md 10.7.
 *
 * The builder runs on every answer, so everything is cached, misses included, until [KanjiDictionary.generation]
 * changes (a package install or a gap-fill write). [prefetch] fills the cache for a whole build with a few chunked
 * `IN` reads of two narrow columns instead of three queries and a full parse per entry.
 */
class KanjiSource(private val dict: KanjiDictionary) {
    constructor(dict: InstalledDictionary) : this(KanjiDictionary.of(dict))

    /** Per entry: the union of `ke_inf` tags by form text, and per sense (applies_to_kanji, is `uk`) in order. */
    private class Entry(val formTags: Map<String, Set<String>>, val senses: List<Pair<List<String>, Boolean>>)

    private val miss = Entry(emptyMap(), emptyList())
    private val entries = ConcurrentHashMap<Long, Entry>()
    private val facts = ConcurrentHashMap<String, KanjiFacts>()
    @Volatile private var generation = Long.MIN_VALUE

    private fun fresh() {
        val g = dict.generation
        if (g != generation) synchronized(this) {
            if (g != generation) {
                entries.clear()
                facts.clear()
                generation = g
            }
        }
    }

    /** Loads the entries and kanji not cached yet in one pass. */
    fun prefetch(seqs: Collection<Long>, literals: Collection<String>) {
        fresh()
        val needSeqs = seqs.filter { !entries.containsKey(it) }.distinct()
        if (needSeqs.isNotEmpty()) {
            val found = dict.formJson(needSeqs)
            for (seq in needSeqs) entries[seq] = found[seq]?.let(::parse) ?: miss
        }
        val needLits = literals.filter { !facts.containsKey(it) }.distinct()
        if (needLits.isNotEmpty()) {
            val found = dict.kanjiFacts(needLits)
            for (l in needLits) facts[l] = found[l]?.let { KanjiFacts(l, it.first, it.second) } ?: KanjiFacts(l, null, null)
        }
    }

    /** The [WordForm] of a note showing [expression], reached through entry [seq] (null: a note without an entry). */
    fun form(seq: Long?, expression: String): WordForm {
        if (seq == null) return WordForm(expression, emptySet(), false)
        prefetch(listOf(seq), emptyList())
        val e = entries.getValue(seq)
        val uk = e.senses.firstOrNull { (appliesTo, _) -> "*" in appliesTo || expression in appliesTo }?.second ?: false
        return WordForm(expression, e.formTags[expression].orEmpty(), uk)
    }

    /** Facts of [literal]; no row gives both null. */
    fun facts(literal: String): KanjiFacts {
        prefetch(emptyList(), listOf(literal))
        return facts.getValue(literal)
    }

    private fun parse(j: InstalledDictionary.FormJson): Entry {
        val forms = HashMap<String, MutableSet<String>>()
        runCatching {
            val a = JSONArray(j.kanjiForms)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val text = o.optString("text").takeIf { it.isNotEmpty() } ?: continue
                forms.getOrPut(text) { HashSet() } += strings(o.optJSONArray("tags")) // every element of the form's text counts
            }
        }
        val senses = runCatching {
            val a = JSONArray(j.senses)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                strings(o.optJSONArray("applies_to_kanji")) to (KanjiRequirement.MISC_USUALLY_KANA in strings(o.optJSONArray("misc")))
            }
        }.getOrDefault(emptyList())
        return Entry(forms, senses)
    }

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()

    companion object {
        /** No dictionary: every word requires no kanji (core README "C-27", entry missing on the device). */
        val NONE: KanjiSource = KanjiSource(KanjiDictionary.NONE)
    }
}
