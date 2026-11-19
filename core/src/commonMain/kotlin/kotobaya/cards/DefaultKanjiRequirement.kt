package kotobaya.cards

import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.KanjiRequirementResult
import kotobaya.contracts.cards.WordForm

/**
 * The one [KanjiRequirement] (ADR-042 as amended by ADR-045 and ADR-046): which kanji a vocab word requires and whether
 * its front shows furigana. Queue loaders (`requiredKanji`), materialization, backfill and the card front all use it.
 * Kana words (ADR-046 (a)) are decided by the dictionary build (`headword`), never here.
 */
public object DefaultKanjiRequirement : KanjiRequirement {
    private val NONE = KanjiRequirementResult(wordExempt = false, learnableKanji = emptyList(), frontFurigana = false)

    override fun evaluate(form: WordForm, kanji: List<KanjiFacts>, exemptionsEnabled: Boolean): KanjiRequirementResult {
        val all = DefaultCardGenerator.kanjiOf(form.expression)
        if (all.isEmpty()) return NONE
        if (!exemptionsEnabled) return KanjiRequirementResult(wordExempt = false, learnableKanji = all, frontFurigana = false)
        val tagged = form.usuallyKana || form.formTags.any { it in KanjiRequirement.EXEMPT_FORM_TAGS }
        val facts = LinkedHashMap<String, KanjiFacts>()
        for (f in kanji) if (f.literal !in facts) facts[f.literal] = f
        // ADR-046 (b): a tagged word requires its jōyō kanji only; ADR-045: a tag never removes a jōyō kanji.
        val required = all.filter { l ->
            val f = facts[l] ?: return@filter false
            if (tagged) KanjiRequirement.isJoyo(f.grade) else isLearnable(f.grade, f.freqRank)
        }
        return KanjiRequirementResult(wordExempt = tagged, learnableKanji = required, frontFurigana = tagged || required.isEmpty())
    }

    override fun isLearnable(grade: Int?, freqRank: Int?): Boolean =
        KanjiRequirement.isJoyo(grade) || (freqRank != null && freqRank in 1..KanjiRequirement.FREQ_TOP)
}
