package kotobaya.cards

import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.KanjiRequirementResult
import kotobaya.contracts.cards.WordForm

/**
 * The one [KanjiRequirement] (ADR-042): which kanji a vocab word requires and whether its front shows
 * furigana. Queue loaders (`requiredKanji`), materialization, backfill and the card front all use it.
 */
public object DefaultKanjiRequirement : KanjiRequirement {
    private val NONE = KanjiRequirementResult(wordExempt = false, learnableKanji = emptyList(), frontFurigana = false)

    override fun evaluate(form: WordForm, kanji: List<KanjiFacts>, exemptionsEnabled: Boolean): KanjiRequirementResult {
        val all = DefaultCardGenerator.kanjiOf(form.expression)
        if (all.isEmpty()) return NONE
        if (!exemptionsEnabled) return KanjiRequirementResult(wordExempt = false, learnableKanji = all, frontFurigana = false)
        val exempt = form.usuallyKana || form.formTags.any { it in KanjiRequirement.EXEMPT_FORM_TAGS }
        val learnable = if (exempt) {
            emptyList()
        } else {
            val facts = LinkedHashMap<String, KanjiFacts>()
            for (f in kanji) if (f.literal !in facts) facts[f.literal] = f
            all.filter { l -> facts[l]?.let { isLearnable(it.grade, it.freqRank) } ?: false }
        }
        return KanjiRequirementResult(wordExempt = exempt, learnableKanji = learnable, frontFurigana = learnable.isEmpty())
    }

    override fun isLearnable(grade: Int?, freqRank: Int?): Boolean =
        (grade != null && (grade in 1..6 || grade == 8)) || (freqRank != null && freqRank in 1..KanjiRequirement.FREQ_TOP)
}
