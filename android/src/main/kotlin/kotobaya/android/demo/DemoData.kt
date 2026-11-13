package kotobaya.android.demo

import android.content.res.AssetManager
import java.time.Instant
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI-FOUND-APP: the shared demo dataset (fixtures/demo/, CC0; see its README) as the Android demo mode, the Compose
 * previews and the screenshot harness read it. The debug build carries the JSON files as assets under `demo/`
 * (build.gradle.kts `copyDemoAssets`); nothing here touches the real stores.
 *
 * A [scenario] is the dataset with the scenario's `empty` keys cleared (scenarios.json). "Now" for the dataset is
 * [NOW_MS]; due dates are relative to it.
 */
class DemoData private constructor(private val files: Map<String, JSONObject>, val scenario: String) {
    private val cleared: Set<String> = files.getValue("scenarios").optJSONObject(scenario)?.optJSONArray("empty").strings().toSet()

    private fun file(name: String): JSONObject = files.getValue(name)

    private fun array(file: String, key: String): List<JSONObject> =
        if (key in cleared) emptyList() else file(file).optJSONArray(key).objects()

    private fun map(file: String, key: String): JSONObject = if (key in cleared) JSONObject() else file(file).optJSONObject(key) ?: JSONObject()

    // --- account.json ---
    val meta: JSONObject get() = file("account").getJSONObject("meta")
    val noteTypes: List<JSONObject> get() = file("account").optJSONArray("note_types").objects()
    val devices: List<JSONObject> get() = file("account").optJSONArray("devices").objects()

    // --- dictionary.json (never cleared: the dictionary is not account data) ---
    val entries: List<JSONObject> get() = file("dictionary").optJSONArray("entries").objects()
    val kanji: List<JSONObject> get() = file("dictionary").optJSONArray("kanji").objects()

    fun entry(seq: Long): JSONObject? = entries.firstOrNull { it.optLong("seq") == seq }

    // --- learning.json ---
    val notes: List<JSONObject> get() = array("learning", "notes")
    val cards: List<JSONObject> get() = notes.flatMap { it.optJSONArray("cards").objects() }
    val decks: List<JSONObject> get() = array("learning", "decks")

    fun deckItems(deckId: String): List<JSONObject> = map("learning", "deck_items").optJSONArray(deckId).objects()

    val hotWords: List<JSONObject> get() = array("learning", "hot_words")
    val hotKanji: List<JSONObject> get() = array("learning", "hot_kanji")
    val confusables: List<JSONObject> get() = array("learning", "confusables")

    // --- media.json ---
    val media: List<JSONObject> get() = array("media", "media")
    val sentences: List<JSONObject> get() = array("media", "sentences")

    fun coverage(mediaId: String): JSONObject? = map("media", "coverage").optJSONObject(mediaId)

    companion object {
        /** "Now" of the dataset: 2026-11-13T09:00:00Z. */
        val NOW_MS: Long = Instant.parse("2026-11-13T09:00:00Z").toEpochMilli()

        /** First instant of the next study day (rollover 04:00 UTC). */
        val DAY_END_MS: Long = Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()

        val SCENARIOS: List<String> = listOf("default", "due900", "empty")

        private val FILES = listOf("account", "dictionary", "learning", "media", "stats", "scenarios")

        /** Reads `demo/<name>.json` from the (debug) assets. */
        fun load(assets: AssetManager, scenario: String = "default"): DemoData =
            DemoData(FILES.associateWith { name -> JSONObject(assets.open("demo/$name.json").bufferedReader().use { it.readText() }) }, scenario)

        /** True when this build carries the dataset (debug builds). */
        fun available(assets: AssetManager): Boolean = runCatching { assets.open("demo/scenarios.json").close() }.isSuccess
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** RFC 3339 instant to epoch ms, or null when [s] is not one. */
internal fun instantMs(s: String): Long? = if (s.length >= 20 && s[4] == '-' && s[10] == 'T') runCatching { Instant.parse(s).toEpochMilli() }.getOrNull() else null

/**
 * A wire object as a replica row: keys in [drop] removed, RFC 3339 instants turned into epoch ms (the replica stores
 * instants as integers, SCHEMA.md), everything else as the same JSON value.
 */
internal fun JSONObject.toRow(vararg drop: String): Map<String, JsonValue> {
    val out = LinkedHashMap<String, JsonValue>()
    for (k in keys()) {
        if (k in drop) continue
        val v = opt(k)
        out[k] = if (v is String) instantMs(v)?.let { JsonNumber(it.toDouble()) } ?: JsonString(v) else toJson(v)
    }
    return out
}

internal fun toJson(v: Any?): JsonValue = when (v) {
    null, JSONObject.NULL -> JsonNull
    is Boolean -> JsonBool(v)
    is Number -> JsonNumber(v.toDouble())
    is String -> JsonString(v)
    is JSONArray -> JsonArray((0 until v.length()).map { toJson(v.opt(it)) })
    is JSONObject -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> v.keys().forEach { k -> m[k] = toJson(v.opt(k)) } })
    else -> JsonString(v.toString())
}
