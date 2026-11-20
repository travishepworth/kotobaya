package kotobaya.android.shell.api.grammar

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.timestamp
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRequest

/** A manual span's key (server `GrammarSpansApi.Key`). */
private class SpanKey(val point: String, val sentence: String, val start: Int, val end: Int) {
    fun tableKey(): List<JsonValue> =
        listOf(JsonString(sentence), JsonString(point), JsonNumber(start.toDouble()), JsonNumber(end.toDouble()), JsonString("manual"))

    fun payload(): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "sentence_id" to JsonString(sentence),
        "grammar_point_id" to JsonString(point),
        "start_offset" to JsonNumber(start.toDouble()),
        "end_offset" to JsonNumber(end.toDouble()),
    )
}

/** Path checks (server `GrammarSpansApi.key`): non-UUID ids `404`; offsets `422` at their parameter. */
private fun key(call: LocalCall): SpanKey {
    val point = call.pathId("grammar_point_id", "grammar point")
    val sentence = call.pathId("sentence_id", "sentence")
    val e = FieldErrors()
    fun offset(name: String, min: Long): Int? {
        val n = call.path(name).toLongOrNull()
        if (n == null || n < min || n > Int.MAX_VALUE - 1) {
            e.add("/$name", "invalid_value", "Must be an integer of at least $min.")
            return null
        }
        return n.toInt()
    }
    val start = offset("start_offset", 0)
    val end = offset("end_offset", 1)
    if (start != null && end != null && end <= start) e.add("/end_offset", "invalid_value", "Must be greater than start_offset.")
    e.throwIfAny()
    return SpanKey(point, sentence, start ?: 0, end ?: 1)
}

private fun span(t: LearningTables, k: SpanKey): Row? = t.get(LearningTable.SENTENCE_GRAMMAR_SPAN, k.tableKey())

/** `GrammarSpan` of a manual row. */
private fun spanView(k: SpanKey, r: Row): JsonObject = jsonObj(
    "sentence_id" to JsonString(k.sentence),
    "grammar_point_id" to JsonString(k.point),
    "start_offset" to JsonNumber(k.start.toDouble()),
    "end_offset" to JsonNumber(k.end.toDouble()),
    "origin" to JsonString("manual"),
    "media_id" to (r["media_id"] as? JsonString ?: JsonNull),
    "rule_index" to JsonNull,
    "created_at" to ((r["created_at"] as? JsonNumber)?.value?.toLong()?.let { JsonString(timestamp(it)) } ?: JsonNull),
)

private fun writes(call: LocalCall): LocalWrites<SqlLearningTables> = call.services.get()

/**
 * `putGrammarSpan` (server `GrammarSpansApi.put`, SHELL.md 4.3): the point must be in the replica (`404`) and live
 * (`410`). A sentence on the device (its media's snapshot installed): `end_offset` beyond its UTF-16 length `422`, and
 * `media_id` is the sentence's; otherwise the span is stored as given with the body's `media_id` (or null). A live
 * manual span with this key → `200` with it, nothing minted; else one `grammar.span_put`.
 */
internal fun putGrammarSpan(call: LocalCall): ShellResponse {
    call.query()
    val k = key(call)
    val body = if (call.request.body.isNullOrEmpty()) JsonObject(emptyMap()) else call.bodyObject()
    val e = FieldErrors()
    e.unknownKeys(body, setOf("media_id"))
    var hint: String? = null
    body.members["media_id"]?.let { v -> if (v != JsonNull) hint = e.uuidValue(v, "/media_id") }
    e.throwIfAny()
    val view = writes(call).exclusive<JsonObject> { tx ->
        val t = tx.learning
        val point = grammarRow(t, k.point) ?: noGrammarPoint()
        if (!point["deleted_at"].isNullish()) grammarGone(point)
        var media = hint
        t.get(LearningTable.SENTENCE, listOf(JsonString(k.sentence)))?.let { s ->
            val length = s["text"].text().orEmpty().length // UTF-16 code units, as the offsets
            if (k.end > length) FieldErrors().apply { add("/end_offset", "out_of_range", "Beyond the sentence text (length $length).") }.throwIfAny()
            media = s["media_id"].text()
        }
        val existing = span(t, k)
        if (existing != null && existing["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(spanView(k, existing))
        val payload = k.payload()
        payload["media_id"] = media?.let(::JsonString) ?: JsonNull
        WritePlan.Mint(listOf(MintRequest(OpType.GRAMMAR_SPAN_PUT, JsonObject(payload)))) { after, _ ->
            spanView(k, checkNotNull(span(after.learning, k)) { "span vanished" })
        }
    }
    return ShellResponse.json(view)
}

/**
 * `removeGrammarSpan` (server `GrammarSpansApi.remove`): unknown point `404`; no live manual span with this key `204`,
 * nothing minted; else one `grammar.span_removed`. A deleted point is no refusal here (as the server).
 */
internal fun removeGrammarSpan(call: LocalCall): ShellResponse {
    call.query()
    val k = key(call)
    writes(call).exclusive<Unit> { tx ->
        grammarRow(tx.learning, k.point) ?: noGrammarPoint()
        val existing = span(tx.learning, k)
        if (existing == null || !existing["deleted_at"].isNullish()) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(listOf(MintRequest(OpType.GRAMMAR_SPAN_REMOVED, JsonObject(k.payload())))) { _, _ -> }
    }
    return ShellResponse.noContent()
}
