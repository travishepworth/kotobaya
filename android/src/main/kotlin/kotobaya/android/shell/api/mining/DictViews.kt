package kotobaya.android.shell.api.mining

import kotobaya.android.dict.DictEntryDetail
import kotobaya.android.dict.DictSummary
import kotobaya.android.dict.KanjiDetail
import kotobaya.android.shell.api.actions.CodePointOrder
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.text
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import org.json.JSONArray

/**
 * The dictionary views of openapi.yaml (`DictEntry`, `DictEntrySummary`, `KanjiWord`, `DictHeadword`, `Kanji`), shaped
 * like the server's `DictApi` / `KanjiWordsApi` (same keys, same order), built from L-7 lookup results (installed
 * package or sidecar cache). The per-user overlay (`status`, `note_id`, `deck_ids`, `decks`) always comes from the
 * replica, also on a forwarded answer (L-7 "recomputed from the replica": read-your-writes).
 */
internal object DictViews {
    private fun n(v: Long): JsonNumber = JsonNumber(v.toDouble())

    private fun nOrNull(v: Int?): JsonValue = v?.let { JsonNumber(it.toDouble()) } ?: JsonNull

    private fun strings(v: List<String>): JsonArray = JsonArray(v.map(::JsonString))

    /** An `org.json` array (the package's JSON columns) as the shell's JSON model. */
    fun json(a: JSONArray): JsonValue = DefaultCanonicalJson.decode(a.toString()).value ?: JsonArray(emptyList())

    /** English glosses of the first sense (server: `senses->0->'gloss'` texts). */
    fun firstSenseGlosses(e: DictEntryDetail): List<String> {
        val gl = e.senses.optJSONObject(0)?.optJSONArray("gloss") ?: return emptyList()
        return (0 until gl.length()).mapNotNull { gl.optJSONObject(it)?.optString("text") }
    }

    // ------------------------------------------------------------------------------------------------ overlay

    /** Effective word status (`new` without a row). */
    fun status(t: LearningTables, seq: Long): String =
        t.get(LearningTable.WORD_STATUS, listOf(n(seq)))?.get("status").text() ?: "new"

    /** A live note holding [seq] (server: `deleted_at is null limit 1`; the oldest when a pre-merge duplicate exists). */
    fun noteId(t: LearningTables, seq: Long): String? = oldestLive(t.where(LearningTable.NOTE, "entry_seq", n(seq)))

    /** The live kanji note of [literal] (`getKanji` overlay). */
    fun kanjiNoteId(t: LearningTables, literal: String): String? =
        oldestLive(t.where(LearningTable.NOTE, "kanji_literal", JsonString(literal)))

    private fun oldestLive(rows: List<Row>): String? = rows.filter { it["deleted_at"].isNullish() && it["merged_into_id"].isNullish() }
        .minWithOrNull(compareBy<Row>({ (it["created_at"] as? JsonNumber)?.value ?: 0.0 }, { it["id"].text().orEmpty() }))
        ?.get("id").text()

    /** Live decks holding a live item `e:<seq>` (any type), each with its item row. */
    private fun entryDecks(t: LearningTables, seq: Long): List<Pair<Row, Row>> =
        t.where(LearningTable.DECK_ITEM, "entry_seq", n(seq)).filter { it["deleted_at"].isNullish() }
            .mapNotNull { item -> item["deck_id"].text()?.let { t.get(LearningTable.DECK, listOf(JsonString(it))) }?.takeIf { it["deleted_at"].isNullish() }?.let { it to item } }
            .distinctBy { it.first["id"].text() }

    /** C-25 `deck_ids` of an entry: draw order (priority, id). */
    fun deckIds(t: LearningTables, seq: Long): List<String> = entryDecks(t, seq)
        .map { it.first }
        .sortedWith(compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { it["id"].text().orEmpty() }))
        .mapNotNull { it["id"].text() }

    /** C-17 `decks` of a `KanjiWord`: by name (code points), then id. */
    fun kanjiWordDecks(t: LearningTables, seq: Long): JsonArray = JsonArray(
        entryDecks(t, seq)
            .sortedWith(compareBy<Pair<Row, Row>, String>(CodePointOrder) { it.first["name"].text().orEmpty() }.thenBy { it.first["id"].text().orEmpty() })
            .map { (deck, item) ->
                jsonObj(
                    "deck_id" to (deck["id"] ?: JsonNull),
                    "name" to (deck["name"] ?: JsonString("")),
                    "type" to (deck["type"] ?: JsonString("user")),
                    "occurrence_count" to ((item["occurrence_count"] as? JsonNumber) ?: JsonNumber(0.0)),
                )
            },
    )

    /** [item] (a server or local summary) with its overlay recomputed from [t]; [kanjiWord] also replaces `decks`. */
    fun withOverlay(t: LearningTables, item: JsonObject, kanjiWord: Boolean): JsonObject {
        val seq = (item.members["seq"] as? JsonNumber)?.value?.toLong() ?: return item
        val m = LinkedHashMap(item.members)
        m["status"] = JsonString(status(t, seq))
        m["note_id"] = noteId(t, seq)?.let(::JsonString) ?: JsonNull
        m["deck_ids"] = strings(deckIds(t, seq))
        if (kanjiWord) m["decks"] = kanjiWordDecks(t, seq)
        return JsonObject(m)
    }

    // ------------------------------------------------------------------------------------------------ views

    fun entry(t: LearningTables, e: DictEntryDetail): JsonObject = jsonObj(
        "seq" to n(e.seq),
        "language" to JsonString(e.language),
        "headword" to JsonString(e.headword),
        "headword_reading" to JsonString(e.headwordReading),
        "is_common" to JsonBool(e.isCommon),
        "kanji_forms" to json(e.kanjiForms),
        "readings" to json(e.readings),
        "senses" to json(e.senses),
        "pos" to strings(e.pos),
        "misc" to strings(e.misc),
        "freq_rank" to nOrNull(e.freqRank),
        "retired" to JsonBool(e.retired),
        "freqs" to JsonArray(e.freqs.map { jsonObj("list_id" to JsonString(it.listId), "rank" to JsonNumber(it.rank.toDouble())) }),
        "pitch" to JsonArray(e.pitch.map { p -> jsonObj("reading" to JsonString(p.reading), "positions" to JsonArray(p.positions.map { JsonNumber(it.toDouble()) })) }),
        "kanji" to strings(e.kanji),
        "status" to JsonString(status(t, e.seq)),
        "note_id" to (noteId(t, e.seq)?.let(::JsonString) ?: JsonNull),
        "deck_ids" to strings(deckIds(t, e.seq)),
    )

    /** `DictEntrySummary` (and with [kanjiWord] the `KanjiWord` extension `decks`). */
    fun summary(t: LearningTables, e: DictEntryDetail, kanjiWord: Boolean = false): JsonObject {
        val base = jsonObj(
            "seq" to n(e.seq),
            "headword" to JsonString(e.headword),
            "headword_reading" to JsonString(e.headwordReading),
            "is_common" to JsonBool(e.isCommon),
            "glosses" to strings(firstSenseGlosses(e)),
            "pos" to strings(e.pos),
            "freq_rank" to nOrNull(e.freqRank),
            "retired" to JsonBool(e.retired),
        )
        return withOverlay(t, base, kanjiWord)
    }

    /** `DictEntrySummary` of a package search hit. */
    fun summary(t: LearningTables, s: DictSummary): JsonObject = withOverlay(
        t,
        jsonObj(
            "seq" to n(s.seq),
            "headword" to JsonString(s.headword),
            "headword_reading" to JsonString(s.headwordReading),
            "is_common" to JsonBool(s.isCommon),
            "glosses" to strings(s.glosses),
            "pos" to strings(s.pos),
            "freq_rank" to nOrNull(s.freqRank),
            "retired" to JsonBool(s.retired),
        ),
        kanjiWord = false,
    )

    fun headword(e: DictEntryDetail): JsonObject = jsonObj(
        "seq" to n(e.seq),
        "headword" to JsonString(e.headword),
        "headword_reading" to JsonString(e.headwordReading),
        "glosses" to strings(firstSenseGlosses(e)),
    )

    fun kanji(t: LearningTables, k: KanjiDetail): JsonObject = jsonObj(
        "literal" to JsonString(k.literal),
        "on_readings" to strings(k.onReadings),
        "kun_readings" to strings(k.kunReadings),
        "nanori" to strings(k.nanori),
        "meanings" to strings(k.meanings),
        "stroke_count" to nOrNull(k.strokeCount),
        "grade" to nOrNull(k.grade),
        "jlpt" to nOrNull(k.jlpt),
        "freq_rank" to nOrNull(k.freqRank),
        "radical" to (k.radical?.let(::JsonString) ?: JsonNull),
        "components" to strings(k.components),
        "note_id" to (kanjiNoteId(t, k.literal)?.let(::JsonString) ?: JsonNull),
    )
}
