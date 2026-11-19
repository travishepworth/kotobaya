package kotobaya.dict

import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.dict.KanjiPart
import kotobaya.contracts.dict.KanjiPartSource
import kotobaya.contracts.dict.KanjiParts
import kotobaya.contracts.dict.KanjiReadingType
import kotobaya.contracts.dict.KanjiReadings
import kotobaya.contracts.dict.UsedReading

/**
 * The one [KanjiParts] (ADR-041): the kanji tiles of a vocab card's back. The alignment is
 * [DefaultKanjiExamplePicker.classify]'s (same forms, same fit test), so `usedReading == null` iff
 * `classify == OTHER` and otherwise the types agree.
 */
public object DefaultKanjiParts : KanjiParts {
    private val preference: Comparator<DefaultKanjiExamplePicker.Form> =
        compareByDescending<DefaultKanjiExamplePicker.Form> { it.text.length }
            .thenBy { if (it.type == KanjiReadingType.ON) 0 else 1 }
            .thenBy { it.rank }
            .thenBy { it.sourceIndex }

    override fun usedReading(kanji: KanjiReadings, headword: String, reading: String): UsedReading? {
        val best = DefaultKanjiExamplePicker.fittingForms(kanji, headword, reading).minWithOrNull(preference) ?: return null
        return UsedReading(best.type, best.source, best.text)
    }

    override fun build(expression: String, reading: String, sources: List<KanjiPartSource>): List<KanjiPart> {
        val byLiteral = LinkedHashMap<String, KanjiPartSource>()
        for (s in sources) if (s.readings.literal !in byLiteral) byLiteral[s.readings.literal] = s
        return DefaultCardGenerator.kanjiOf(expression).take(KanjiParts.MAX_PARTS).map { literal ->
            val s = byLiteral[literal]
            val readings = s?.readings ?: KanjiReadings(literal, emptyList(), emptyList())
            val meanings = s?.meanings.orEmpty()
            KanjiPart(
                literal = literal,
                keyword = s?.noteKeyword?.takeIf { it.isNotBlank() } ?: meanings.firstOrNull(),
                meanings = meanings,
                on = readings.onReadings,
                kun = readings.kunReadings,
                usedReading = usedReading(readings, expression, reading),
            )
        }
    }
}
