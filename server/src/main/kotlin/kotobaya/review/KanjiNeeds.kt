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
    private class EntryCache(val token: String) {
        val entries = java.util.concurrent.ConcurrentHashMap<Long, Entry>()
    }

    private companion object {
        /** Bounds memory if something walks the whole dictionary through here; beyond it misses are just not cached. */
        const val ENTRY_CACHE_MAX = 100_000

        @Volatile
        var entryCache: EntryCache? = null
    }

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
        // By literal, first row wins (as the requirement itself dedups). Each word gets only its own kanji's facts:
        // the requirement indexes the list it is given, so handing every word the whole page's facts was
        // O(words × kanji) per call (~15M map inserts for a 7.5k-word queue).
        val factsByLiteral = HashMap<String, KanjiFacts>()
        if (enabled) for (f in facts(literals)) factsByLiteral.putIfAbsent(f.literal, f)
        return words.associate { w ->
            val (expression, reading) = forms.getValue(w.key)
            val entry = w.entrySeq?.let(entries::get)
            val tags = entry?.formTags.orEmpty().filter { it.first == expression }.flatMap { it.second }.toSet()
            val uk = entry?.senses?.firstOrNull { "*" in it.appliesTo || expression in it.appliesTo }?.usuallyKana ?: false
            val expressionKanji = DefaultCardGenerator.kanjiOf(expression)
            val wordFacts = expressionKanji.mapNotNull(factsByLiteral::get)
            val result = requirement.evaluate(WordForm(expression, tags, uk), wordFacts, enabled)
            w.key to ResolvedWord(expression, reading, expressionKanji, result.learnableKanji, result.frontFurigana)
        }
    }

    /**
     * [Entry] rows are pure functions of `ref.dict_entry`, which only the worker's dictionary load writes, in one
     * transaction that ends by setting `is_current` on its release row. That row's `(id, xmin)` therefore changes with
     * every load (even a reload of the same id), and keys a process-wide cache: a queue build otherwise re-shapes the
     * jsonb of every vocab entry (~45 ms for 7.5k words) on each request. A map is only ever filled by requests that
     * read its token; a new token replaces the map.
     */
    private fun entries(seqs: Set<Long>): Map<Long, Entry> {
        if (seqs.isEmpty()) return emptyMap()
        val token = dsl.fetchOptional("select id::text || ':' || xmin::text from ref.dict_release where is_current")
            .map { it.get(0, String::class.java) }.orElse(null)
            ?: return fetchEntries(seqs)
        val cache = entryCache.let { c ->
            if (c != null && c.token == token) c else EntryCache(token).also { entryCache = it }
        }
        val out = HashMap<Long, Entry>(seqs.size * 2)
        val missing = ArrayList<Long>()
        for (s in seqs) cache.entries[s]?.let { out[s] = it } ?: missing.add(s)
        if (missing.isNotEmpty()) {
            val fetched = fetchEntries(missing.toSet())
            if (cache.entries.size + fetched.size <= ENTRY_CACHE_MAX) cache.entries.putAll(fetched)
            out.putAll(fetched)
        }
        return out
    }

    private fun fetchEntries(seqs: Set<Long>): Map<Long, Entry> {
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
