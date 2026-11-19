package kotobaya.review

import kotobaya.render.FieldPlainText
import kotobaya.render.PlainTextMode
import kotobaya.cards.DefaultCardGenerator
import kotobaya.cards.DefaultKanjiRequirement
import kotobaya.contracts.cards.KanjiFacts
import kotobaya.contracts.cards.KanjiRequirement
import kotobaya.contracts.cards.WordForm
import kotobaya.contracts.sync.JsonValue
import kotobaya.server.json
import kotobaya.sync.SyncJson
import org.jooq.DSLContext

/** The typed setting `cards.kanji_exemptions` (ADR-042, SCHEMA.md 6.5): false only for JSON `false`. Read once per operation. */
internal object KanjiExemptionSetting {
    fun load(dsl: DSLContext): Boolean {
        val text = dsl.fetchOptional("select value::text from app.settings where key = ?", KanjiRequirement.SETTING_KANJI_EXEMPTIONS)
            .map { it.get(0, String::class.java) }.orElse(null)
        val value: JsonValue? = text?.let { SyncJson.fromJackson(json.readTree(it)) }
        return KanjiRequirement.exemptionsEnabled(value)
    }
}

/** One vocab note (or a pending entry) to evaluate: its `expression` / `reading` field values (raw, may be null) and entry. */
internal class WordRef(val key: String, val expressionField: String?, val readingField: String?, val entrySeq: Long?)

/** [KanjiRequirement] applied to one [WordRef], with the texts it was applied to. */
internal class ResolvedWord(
    val expression: String,
    val reading: String,
    /** `CardGenerator.kanjiOf(expression)`. */
    val expressionKanji: List<String>,
    /** `KanjiRequirementResult.learnableKanji`. */
    val required: List<String>,
    val frontFurigana: Boolean,
)

/**
 * ADR-042: feeds the core [KanjiRequirement] with this replica's data, for a whole page or queue in a fixed number
 * of statements (entry forms and senses, kanji facts). It applies no rule itself. Expression = a non-blank
 * `expression` field, else the entry's headword; reading likewise (contracts/core README "C-27").
 */
internal class KanjiNeeds(
    private val dsl: DSLContext,
    private val enabled: Boolean = KanjiExemptionSetting.load(dsl),
    private val requirement: KanjiRequirement = DefaultKanjiRequirement,
) {
    private class SenseUse(val appliesTo: Set<String>, val usuallyKana: Boolean)
    private class Entry(val headword: String, val reading: String, val formTags: List<Pair<String, List<String>>>, val senses: List<SenseUse>)

    fun resolve(words: Collection<WordRef>): Map<String, ResolvedWord> {
        if (words.isEmpty()) return emptyMap()
        val entries = entries(words.mapNotNull { it.entrySeq }.toSet())
        val forms = LinkedHashMap<String, Pair<String, String>>()
        for (w in words) {
            val entry = w.entrySeq?.let(entries::get)
            val e = FieldPlainText.of(w.expressionField, PlainTextMode.KANJI).ifEmpty { entry?.headword.orEmpty() }
            val r = FieldPlainText.of(w.readingField, PlainTextMode.KANA).ifEmpty { entry?.reading.orEmpty() }
            forms[w.key] = e to r
        }
        val literals = forms.values.flatMap { DefaultCardGenerator.kanjiOf(it.first) }.toSet()
        val facts = if (enabled) facts(literals) else emptyList()
        return words.associate { w ->
            val (expression, reading) = forms.getValue(w.key)
            val entry = w.entrySeq?.let(entries::get)
            val tags = entry?.formTags.orEmpty().filter { it.first == expression }.flatMap { it.second }.toSet()
            val uk = entry?.senses?.firstOrNull { "*" in it.appliesTo || expression in it.appliesTo }?.usuallyKana ?: false
            val result = requirement.evaluate(WordForm(expression, tags, uk), facts, enabled)
            w.key to ResolvedWord(expression, reading, DefaultCardGenerator.kanjiOf(expression), result.learnableKanji, result.frontFurigana)
        }
    }

    private fun entries(seqs: Set<Long>): Map<Long, Entry> {
        if (seqs.isEmpty()) return emptyMap()
        return dsl.fetch(
            "select e.seq, e.headword, e.headword_reading, " +
                "coalesce((select jsonb_agg(jsonb_build_object('t', kf->>'text', 'g', kf->'tags')) from jsonb_array_elements(e.kanji_forms) kf " +
                "where jsonb_typeof(kf->'tags') = 'array' and jsonb_array_length(kf->'tags') > 0), '[]'::jsonb)::text, " +
                "coalesce((select jsonb_agg(jsonb_build_object('a', case when jsonb_typeof(s->'applies_to_kanji') = 'array' then s->'applies_to_kanji' else '[]'::jsonb end, " +
                "'u', jsonb_typeof(s->'misc') = 'array' and jsonb_exists(s->'misc', 'uk'))) from jsonb_array_elements(e.senses) s), '[]'::jsonb)::text " +
                "from ref.dict_entry e where e.seq = any(array(select unnest(?::bigint[])))",
            seqs.toTypedArray(),
        ).associate { r ->
            val formTags = json.readTree(r.get(3, String::class.java)).map { f -> f["t"].asText() to f["g"].map { it.asText() } }
            val senses = json.readTree(r.get(4, String::class.java)).map { s -> SenseUse(s["a"].map { it.asText() }.toSet(), s["u"].asBoolean()) }
            (r.get(0) as Number).toLong() to Entry(r.get(1, String::class.java), r.get(2, String::class.java), formTags, senses)
        }
    }

    private fun facts(literals: Set<String>): List<KanjiFacts> {
        if (literals.isEmpty()) return emptyList()
        return dsl.fetch(
            "select literal, grade, freq_rank from ref.kanji where literal = any(array(select unnest(?::text[])))",
            literals.toTypedArray(),
        ).map { KanjiFacts(it.get(0, String::class.java), it.get(1, Int::class.javaObjectType), it.get(2, Int::class.javaObjectType)) }
    }
}
