package kotobaya.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Conversions between the core's [JsonValue] (ops) and Jackson (rows as `to_jsonb(t)` /
 * `jsonb_populate_record`), plus small payload accessors. Integral doubles below 2^53 become JSON
 * integers, so `3.0` lands in an `integer` column as `3`.
 */
internal object SyncJson {
    val nodes: JsonNodeFactory = JsonNodeFactory.instance
    private const val MAX_SAFE = 9007199254740991.0

    fun toJackson(v: JsonValue): JsonNode = when (v) {
        JsonNull -> nodes.nullNode()
        is JsonBool -> nodes.booleanNode(v.value)
        is JsonNumber -> {
            val d = v.value
            if (d == Math.floor(d) && Math.abs(d) <= MAX_SAFE) nodes.numberNode(d.toLong()) else nodes.numberNode(d)
        }
        is JsonString -> nodes.textNode(v.value)
        is JsonArray -> nodes.arrayNode().also { a -> v.items.forEach { a.add(toJackson(it)) } }
        is JsonObject -> nodes.objectNode().also { o -> v.members.forEach { (k, x) -> o.set<JsonNode>(k, toJackson(x)) } }
    }

    fun fromJackson(n: JsonNode?): JsonValue = when {
        n == null || n.isNull || n.isMissingNode -> JsonNull
        n.isBoolean -> JsonBool(n.booleanValue())
        n.isNumber -> JsonNumber(n.doubleValue())
        n.isTextual -> JsonString(n.textValue())
        n.isArray -> JsonArray(n.map { fromJackson(it) })
        n.isObject -> JsonObject(LinkedHashMap<String, JsonValue>().also { m -> n.fields().forEach { (k, v) -> m[k] = fromJackson(v) } })
        else -> error("unsupported JSON node ${n.nodeType}")
    }

    /** True iff [v] contains U+0000 in a string or member name (Postgres text/jsonb cannot store it). */
    fun containsNul(v: JsonValue): Boolean = when (v) {
        is JsonString -> v.value.indexOf('\u0000') >= 0
        is JsonArray -> v.items.any(::containsNul)
        is JsonObject -> v.members.any { (k, x) -> k.indexOf('\u0000') >= 0 || containsNul(x) }
        else -> false
    }

    /** JSON equality as SYNC.md 7.2 uses it: structural, numbers compared as doubles. */
    fun jsonEquals(a: JsonNode?, b: JsonNode?): Boolean = fromJackson(a) == fromJackson(b)

    /** RFC 3339 UTC text for a timestamptz column. */
    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /** Epoch ms of a timestamptz as `to_jsonb` renders it, or null. */
    fun ms(n: JsonNode?): Long? = if (n == null || n.isNull || n.isMissingNode) null else OffsetDateTime.parse(n.asText()).toInstant().toEpochMilli()

    fun obj(): ObjectNode = nodes.objectNode()

    fun arr(): ArrayNode = nodes.arrayNode()
}

/** Payload accessors. A missing member and JSON `null` both read as null. */
internal fun JsonObject.member(name: String): JsonValue? = members[name]?.takeUnless { it == JsonNull }

internal fun JsonObject.has(name: String): Boolean = members.containsKey(name)

internal fun JsonObject.str(name: String): String? = (member(name) as? JsonString)?.value

internal fun JsonObject.reqStr(name: String): String = requireNotNull(str(name)) { "payload.$name is required" }

internal fun JsonObject.long(name: String): Long? = (member(name) as? JsonNumber)?.value?.toLong()

internal fun JsonObject.bool(name: String): Boolean? = (member(name) as? JsonBool)?.value

internal fun JsonObject.obj(name: String): JsonObject? = member(name) as? JsonObject

internal fun JsonObject.arr(name: String): JsonArray? = member(name) as? JsonArray

internal fun JsonObject.node(name: String): JsonNode = SyncJson.toJackson(members[name] ?: JsonNull)
