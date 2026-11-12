package kotobaya.settings

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.sync.OpType
import kotobaya.notes.Errors
import kotobaya.notes.Reply
import kotobaya.notes.callerTokenId
import kotobaya.notes.jsonBody
import kotobaya.notes.payload
import kotobaya.notes.ptr
import kotobaya.notes.rest
import kotobaya.notes.send
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import org.jooq.DSLContext

private val SETTING_KEY_RE = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$")
private const val INT4_MIN = -2147483648.0
private const val INT4_MAX = 2147483647.0

/** The validity test of one well-known key (SCHEMA.md 6.1). JSON null is always accepted by the caller. */
private sealed interface KeyType {
    data class Int(val min: Double = INT4_MIN, val max: Double = INT4_MAX) : KeyType
    data class OneOf(val values: Set<Double>) : KeyType
    data object Bool : KeyType
    data object Text : KeyType
}

private val WELL_KNOWN: Map<String, KeyType> = mapOf(
    "scheduler.timezone" to KeyType.Text,
    "scheduler.day_rollover_hour" to KeyType.Int(0.0, 23.0),
    "scheduler.new_per_day" to KeyType.Int(),
    "scheduler.reviews_per_day" to KeyType.Int(),
    "scheduler.new_per_session" to KeyType.Int(),
    "scheduler.grading_buttons" to KeyType.OneOf(setOf(4.0, 5.0)),
    "scheduler.leech_threshold" to KeyType.Int(),
    "scheduler.bury_siblings" to KeyType.Bool,
    "scheduler.kanji_before_vocab" to KeyType.Bool,
    "scheduler.mined_priority" to KeyType.Int(),
    "audio.prefetch_days" to KeyType.Int(),
)

/** An int is valid iff the nearest binary64 is an integer in range; 1.0 and 1e2 are valid, 1e309 (Infinity) is not. */
private fun validInt(v: JsonNode, min: Double, max: Double): Boolean {
    if (!v.isNumber) return false
    val d = v.doubleValue()
    return !d.isNaN() && !d.isInfinite() && d == Math.floor(d) && d >= min && d <= max
}

private fun KeyType.accepts(v: JsonNode): Boolean = when (this) {
    is KeyType.Int -> validInt(v, min, max)
    is KeyType.OneOf -> v.isNumber && v.doubleValue() in values
    KeyType.Bool -> v.isBoolean
    KeyType.Text -> v.isTextual
}

/** `listSettings`, `updateSettings`. */
internal class SettingsApi(private val db: Database, private val opApply: OpApply) {
    suspend fun list(call: ApplicationCall) {
        call.respondJson(mapOf("items" to db.tx { settingList(it) }))
    }

    suspend fun update(call: ApplicationCall) {
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, setOf("values"))
        val values = body.get("values")
        val entries = mutableListOf<Pair<String, JsonNode>>()
        if (values == null) {
            e.add("/values", "required", "values is required.")
        } else if (values !is ObjectNode) {
            e.add("/values", "invalid_type", "Must be an object.")
        } else if (values.isEmpty) {
            e.add("/values", "invalid_value", "At least one setting is required.")
        } else {
            for ((k, v) in values.fields().asSequence().toList()) {
                val at = ptr("values", k)
                if (k.length > 200 || !SETTING_KEY_RE.matches(k)) {
                    e.add(at, "invalid_value", "Keys are dotted lowercase identifiers of at most 200 characters.")
                    continue
                }
                val type = WELL_KNOWN[k]
                if (type != null && !v.isNull && !type.accepts(v)) {
                    e.add(at, "invalid_type", "Not a valid value for $k.")
                    continue
                }
                entries += k to v
            }
        }
        e.throwIfAny()
        entries.sortBy { it.first } // ASCII keys: code-point order

        var current = ""
        val reply = opApply.rest(call.callerTokenId(), { ptr("values", current) }) { tx ->
            for ((k, v) in entries) {
                current = k
                val p = json.createObjectNode().put("key", k)
                p.set<JsonNode>("value", v)
                tx.emit(OpType.SETTINGS_SET, payload(p))
            }
            Reply(HttpStatusCode.OK, json.valueToTree(mapOf("items" to settingList(tx.dsl))), tx.lastSeq)
        }
        call.send(reply)
    }

    private fun settingList(dsl: DSLContext): List<JsonNode> =
        dsl.fetch(
            "select key, value::text, to_char(updated_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') " +
                "from app.settings where value <> 'null'::jsonb order by key collate \"C\"",
        ).map {
            json.createObjectNode().apply {
                put("key", it.get(0, String::class.java))
                set<JsonNode>("value", json.readTree(it.get(1, String::class.java)))
                put("updated_at", it.get(2, String::class.java))
            }
        }
}
