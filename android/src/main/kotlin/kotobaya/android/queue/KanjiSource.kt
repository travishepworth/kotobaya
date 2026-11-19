package kotobaya.android.queue

import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.KanjiDetail
import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.WordForm
import java.util.concurrent.ConcurrentHashMap

/**
 * What core `KanjiRequirement` needs from the on-device dictionary (contracts/core README "C-27"; SHELL.md 4.3, 10.7):
 * the form tags and `uk` of an entry's displayed form ([WordForm]) and the grade / frequency rank of a kanji
 * ([KanjiFacts]). Dictionary data by L-7 steps 1-2 only (installed package, then the sidecar cache; never the network);
 * a miss gives no tags / no facts, exactly the documented difference of SHELL.md 10.7.
 *
 * The builder runs on every answer, so hits are cached for the life of this object (a dictionary update shows after a
 * restart, like the headword cache). A miss is never cached: gap fill may supply it later.
 */
class KanjiSource(
    private val entry: (Long) -> DictEntryDetail?,
    private val kanji: (String) -> KanjiDetail?,
) {
    constructor(dict: InstalledDictionary) : this(dict::entry, dict::kanji)

    private class Senses(val formTags: Map<String, Set<String>>, val senses: List<Pair<List<String>, Boolean>>)

    private val entries = ConcurrentHashMap<Long, Senses>()
    private val facts = ConcurrentHashMap<String, KanjiFacts>()

    /** The [WordForm] of a note showing [expression], reached through entry [seq] (null: a note without an entry). */
    fun form(seq: Long?, expression: String): WordForm {
        val s = seq?.let(::senses) ?: return WordForm(expression, emptySet(), false)
        val tags = s.formTags[expression].orEmpty()
        val uk = s.senses.firstOrNull { (appliesTo, _) -> "*" in appliesTo || expression in appliesTo }?.second ?: false
        return WordForm(expression, tags, uk)
    }

    /** Facts of [literal]; no row (or the dictionary failing) gives both null. */
    fun facts(literal: String): KanjiFacts {
        facts[literal]?.let { return it }
        val k = runCatching { kanji(literal) }.getOrNull() ?: return KanjiFacts(literal, null, null)
        return KanjiFacts(literal, k.grade, k.freqRank).also { facts[literal] = it }
    }

    private fun senses(seq: Long): Senses? {
        entries[seq]?.let { return it }
        val e = runCatching { entry(seq) }.getOrNull() ?: return null
        val forms = HashMap<String, Set<String>>()
        for (i in 0 until e.kanjiForms.length()) {
            val o = e.kanjiForms.optJSONObject(i) ?: continue
            val text = o.optString("text").takeIf { it.isNotEmpty() } ?: continue
            if (text !in forms) forms[text] = strings(o.optJSONArray("tags")).toSet() // a repeated form: the first element
        }
        val senses = (0 until e.senses.length()).mapNotNull { i ->
            val o = e.senses.optJSONObject(i) ?: return@mapNotNull null
            strings(o.optJSONArray("applies_to_kanji")) to (KanjiRequirement.MISC_USUALLY_KANA in strings(o.optJSONArray("misc")))
        }
        return Senses(forms, senses).also { entries[seq] = it }
    }

    companion object {
        /** No dictionary: every word requires no kanji (core README "C-27", entry missing on the device). */
        val NONE: KanjiSource = KanjiSource({ null }, { null })
    }

    private fun strings(a: org.json.JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
}
