package kotobaya.android.shell.api.mining

import kotobaya.android.dict.DictGapCache
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.text
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/**
 * `listEntryExamples.media` on the device (SHELL.md L-7): `origin = media` sentences of live media installed in the
 * replica whose tokens map to the entry (the server resolves `occurrence.sentence_ids`, which the device does not
 * have; a token's `seq` is the same mapping). Fewest unknown words first, then media `created_at` descending, then id
 * (server order). The `Sentence` view is the server's (`EntryExamplesApi.media`).
 */
internal object MediaSentences {
    fun forEntry(cache: DictGapCache, store: SqlDelightDeviceStore, seq: Long, limit: Int): List<JsonObject> {
        // candidates by the stored token JSON (any whitespace, no substring false hits such as 12 for 123); every
        // candidate is then checked on its parsed tokens. Without json1 every media sentence is a candidate.
        val ids = runCatching {
            cache.column(
                "SELECT id FROM sentence WHERE origin = 'media' AND EXISTS " +
                    "(SELECT 1 FROM json_each(sentence.tokens) WHERE json_extract(json_each.value, '\$.seq') = $seq)",
            ) { it.getString(0) }
        }.recoverCatching {
            cache.column("SELECT id FROM sentence WHERE origin = 'media'") { it.getString(0) }
        }.getOrDefault(emptyList()).filterNotNull()
        if (ids.isEmpty()) return emptyList()
        return store.read { tx ->
            val t = tx.learning
            ids.mapNotNull { id ->
                val s = t.get(LearningTable.SENTENCE, listOf(JsonString(id))) ?: return@mapNotNull null
                if (s["origin"].text() != "media" || !tokenSeqs(s).contains(seq)) return@mapNotNull null
                val media = s["media_id"].text()?.let { t.get(LearningTable.MEDIA, listOf(JsonString(it))) } ?: return@mapNotNull null
                if (!media["deleted_at"].isNullish()) return@mapNotNull null
                Triple(s, unknownCount(t, s), (media["created_at"] as? JsonNumber)?.value ?: 0.0)
            }.sortedWith(compareBy<Triple<Row, Int, Double>>({ it.second }, { -it.third }, { it.first["id"].text().orEmpty() }))
                .take(limit)
                .map { (s, unknown, _) -> view(t, s, unknown) }
        }
    }

    private fun tokens(s: Row): List<Map<String, JsonValue>> = when (val v = s["tokens"]) {
        is JsonArray -> v.items
        is JsonString -> (runCatching { DefaultCanonicalJson.decode(v.value).value }.getOrNull() as? JsonArray)?.items.orEmpty()
        else -> emptyList()
    }.mapNotNull { (it as? JsonObject)?.members }

    private fun tokenSeqs(s: Row): Set<Long> = tokens(s).mapNotNullTo(HashSet()) { (it["seq"] as? JsonNumber)?.value?.toLong() }

    /** Distinct mapped content/other entries whose status is `new` (PARSER.md 2, as `GrammarSentenceSource`). */
    private fun unknownCount(t: LearningTables, s: Row): Int = tokens(s).mapNotNullTo(HashSet()) { m ->
        val seq = (m["seq"] as? JsonNumber)?.value?.toLong() ?: return@mapNotNullTo null
        if (m["class"].text() !in setOf("content", "other")) return@mapNotNullTo null
        seq.takeIf { DictViews.status(t, it) == "new" }
    }.size

    private fun view(t: LearningTables, s: Row, unknown: Int): JsonObject {
        val id = s["id"].text().orEmpty()
        val spans = t.where(LearningTable.SENTENCE_GRAMMAR_SPAN, "sentence_id", JsonString(id))
            .filter { it["deleted_at"].isNullish() }
            .filter { sp ->
                val point = sp["grammar_point_id"].text()?.let { t.get(LearningTable.GRAMMAR_POINT, listOf(JsonString(it))) }
                point != null && point["deleted_at"].isNullish()
            }
            .sortedWith(
                compareBy<Row>(
                    { (it["start_offset"] as? JsonNumber)?.value ?: 0.0 },
                    { (it["end_offset"] as? JsonNumber)?.value ?: 0.0 },
                    { it["grammar_point_id"].text().orEmpty() },
                    { it["origin"].text().orEmpty() },
                ),
            )
            .map { sp ->
                jsonObj(
                    "grammar_point_id" to (sp["grammar_point_id"] ?: JsonNull),
                    "start_offset" to (sp["start_offset"] ?: JsonNull),
                    "end_offset" to (sp["end_offset"] ?: JsonNull),
                    "origin" to (sp["origin"] ?: JsonNull),
                )
            }
        val location = when (val l = s["location"]) {
            is JsonString -> runCatching { DefaultCanonicalJson.decode(l.value).value }.getOrNull() ?: JsonNull
            null -> JsonNull
            else -> l
        }
        return jsonObj(
            "id" to JsonString(id),
            "media_id" to (s["media_id"] ?: JsonNull),
            "segment_id" to (s["segment_id"] ?: JsonNull),
            "ordinal" to (s["ordinal"] ?: JsonNull),
            "text" to (s["text"] ?: JsonString("")),
            "tokens" to JsonArray(tokens(s).map { JsonObject(it) }),
            "origin" to (s["origin"] ?: JsonString("media")),
            "start_ms" to (s["start_ms"] ?: JsonNull),
            "end_ms" to (s["end_ms"] ?: JsonNull),
            "audio_blob_hash" to (s["audio_blob_hash"] ?: JsonNull),
            "image_blob_hash" to (s["image_blob_hash"] ?: JsonNull),
            "location" to location,
            "unknown_count" to JsonNumber(unknown.toDouble()),
            "grammar_spans" to JsonArray(spans),
        )
    }
}
