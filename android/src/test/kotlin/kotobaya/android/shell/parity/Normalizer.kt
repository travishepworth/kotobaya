package kotobaya.android.shell.parity

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/** One raw answer: status, header fields (names lower-case), body text (null when empty). */
data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String?)

/**
 * SHELL.md 8.3, for one scenario; a port of the server harness's `kotobaya.parity.Normalizer` (SHELL-P1) onto core's
 * [JsonValue]. Stateful: the `id` and `hlc` counters (and the value -> placeholder maps) run across all steps, so one
 * instance normalises the steps of one scenario in order.
 */
class Normalizer(private val knownIds: Set<String>, private val mintingDevice: String) {
    private val ids = LinkedHashMap<String, String>()
    private val hlcs = LinkedHashMap<String, String>()

    /** Rules 1–6: `{status, headers, body}`. */
    fun step(raw: RawResponse, ignore: List<String> = emptyList()): JsonObject {
        val headers = sortedMapOf<String, JsonValue>()
        val lower = raw.headers.mapKeys { it.key.lowercase() }
        val contentType = lower["content-type"]?.substringBefore(';')?.trim()?.lowercase()
        contentType?.let { headers["content-type"] = JsonString(it) }
        lower["retry-after"]?.let { headers["retry-after"] = JsonString(it) }
        // Rule 5: content-location first, then the body.
        lower["content-location"]?.let { headers["content-location"] = JsonString(string(it)) }
        var body: JsonValue = parseBody(raw.body)
        body = dropMember(body, "generated_at")
        if (contentType == "application/problem+json" && body is JsonObject) body = problem(body)
        body = traverse(body)
        for (p in ignore) body = ParityJson.remove(body, p)
        return JsonObject(
            linkedMapOf(
                "status" to JsonNumber(raw.status.toDouble()),
                "headers" to JsonObject(LinkedHashMap(headers)),
                "body" to body,
            ),
        )
    }

    private fun parseBody(text: String?): JsonValue {
        if (text.isNullOrEmpty()) return JsonNull
        return DefaultCanonicalJson.decode(text).value ?: JsonString(text)
    }

    private fun problem(body: JsonObject): JsonObject {
        val m = LinkedHashMap(body.members)
        listOf("request_id", "title", "detail").forEach { m.remove(it) }
        val errors = m["errors"] as? JsonArray
        if (errors != null) {
            val items = errors.items.map { e -> (e as? JsonObject)?.let { JsonObject(it.members - "detail") } ?: e }
                .sortedWith(compareBy<JsonValue>({ key(it, "pointer") }, { key(it, "code") }))
            m["errors"] = JsonArray(items)
        }
        return JsonObject(m)
    }

    private fun key(e: JsonValue, name: String): String = ((e as? JsonObject)?.members?.get(name) as? JsonString)?.value ?: ""

    private fun dropMember(node: JsonValue, name: String): JsonValue = when (node) {
        is JsonObject -> JsonObject(LinkedHashMap(node.members.filterKeys { it != name }.mapValues { dropMember(it.value, name) }))
        is JsonArray -> JsonArray(node.items.map { dropMember(it, name) })
        else -> node
    }

    /** Depth-first, object members in ascending key order, arrays in index order; objects rebuilt sorted. */
    private fun traverse(node: JsonValue): JsonValue = when (node) {
        is JsonObject -> {
            val out = LinkedHashMap<String, JsonValue>()
            node.members.keys.sorted().forEach { k -> out[k] = traverse(node.members.getValue(k)) }
            JsonObject(out)
        }
        is JsonArray -> JsonArray(node.items.map { traverse(it) })
        is JsonString -> JsonString(string(node.value))
        else -> node
    }

    /** Rules 4 and 5 on one string value. */
    internal fun string(s: String): String {
        if (s == mintingDevice) return "<device>"
        HLC.matchEntire(s)?.let { m ->
            if (m.groupValues[1] == mintingDevice) return hlcs.getOrPut(s) { "<hlc${hlcs.size + 1}>" }
        }
        return UUID.replace(s) { m ->
            val v = m.value
            if (v in knownIds || v.startsWith(BUILT_IN)) v else ids.getOrPut(v) { "<id${ids.size + 1}>" }
        }
    }

    companion object {
        const val BUILT_IN = "00000000-0000-7000-8000-"
        private const val U = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val UUID = Regex("(?<![0-9a-f])$U(?![0-9a-f])")
        val HLC = Regex("^[0-9a-f]{12}-[0-9a-f]{4}-($U)$")
    }
}
