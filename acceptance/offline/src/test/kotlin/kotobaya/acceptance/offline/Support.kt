package kotobaya.acceptance.offline

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.Op
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.DeviceCardCache
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

const val MINUTE: Long = 60_000L
const val HOUR: Long = 3_600_000L
const val DAY: Long = 86_400_000L

/** 2026-11-02T00:00:00Z, a Monday: the start of every simulated week. */
const val WEEK_START: Long = 1_793_577_600_000L

/** Runs a suspend block that never really suspends (in-process transports) to completion. */
fun <T> runSync(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return checkNotNull(outcome) { "the block suspended; simulated transports answer synchronously" }.getOrThrow()
}

/** A JSON object from Kotlin values (null values are omitted). */
fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.filter { it.second != null }.associateTo(LinkedHashMap()) { (k, v) -> k to json(v) },
)

fun json(v: Any?): JsonValue = when (v) {
    null -> JsonNull
    is JsonValue -> v
    is String -> JsonString(v)
    is Boolean -> JsonBool(v)
    is Int -> JsonNumber(v.toDouble())
    is Long -> JsonNumber(v.toDouble())
    is Double -> JsonNumber(v)
    is List<*> -> JsonArray(v.map(::json))
    is Map<*, *> -> JsonObject(v.entries.associateTo(LinkedHashMap()) { (k, x) -> k as String to json(x) })
    else -> error("unsupported $v")
}

fun Row.str(k: String): String? = (this[k] as? JsonString)?.value

fun Row.num(k: String): Long? = (this[k] as? JsonNumber)?.value?.toLong()

fun Row.isNull(k: String): Boolean = (this[k] ?: JsonNull) == JsonNull

fun Op.p(k: String): String? = (payload.members[k] as? JsonString)?.value

/** A table projection: table → canonical key → row. */
typealias Projection = Map<String, Map<String, Row>>

/** The tables of SYNC.md 11 item 1. */
val COMPARED: List<LearningTable> = listOf(
    LearningTable.NOTE_TYPE, LearningTable.NOTE, LearningTable.CARD, LearningTable.REVIEW_LOG,
    LearningTable.WORD_STATUS, LearningTable.DECK, LearningTable.DECK_ITEM, LearningTable.CONFUSABLE,
    LearningTable.GRAMMAR_POINT, LearningTable.SENTENCE_GRAMMAR_SPAN, LearningTable.SETTINGS,
    LearningTable.FSRS_PARAMS, LearningTable.MEDIA,
)

val CACHE_COLUMNS: List<String> = listOf(
    "state", "step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "last_review_id",
    "reps", "lapses", "weights_version",
)

fun keyText(table: LearningTable, row: Row): String = DefaultCanonicalJson.encode(JsonArray(table.keyOf(row)))

/**
 * SYNC.md 11: the projection on which converged replicas are equal. Losers only on `id`,
 * `merged_into_id`, `deleted_at` (item 2); the card cache only for maintained cards (item 3); no
 * `word_status.updated_at` / `auto_status`, `installed_content_version`, snapshot rows (item 4).
 */
fun projection(t: LearningTables): Projection = COMPARED.associate { table -> table.sqlName to projectRows(table, t.all(table)) }

fun projectRows(table: LearningTable, rows: List<Row>): Map<String, Row> = rows.filter { row ->
    when (table) {
        LearningTable.DECK_ITEM -> !row.isNull("hlc")
        LearningTable.SENTENCE_GRAMMAR_SPAN -> row["origin"] == JsonString("manual")
        else -> true
    }
}.map { row -> projectRow(table, row) }.associateBy { keyText(table, it) }

fun projectRow(table: LearningTable, row: Row): Row {
    val out = LinkedHashMap(row)
    if (!row.isNull("merged_into_id")) out.keys.retainAll(setOf("id", "merged_into_id", "deleted_at"))
    if (table == LearningTable.CARD && !isMaintained(row)) CACHE_COLUMNS.forEach { out.remove(it) }
    if (table == LearningTable.WORD_STATUS) {
        out.remove("updated_at")
        out.remove("auto_status")
    }
    out.remove("installed_content_version")
    return out
}

/** Rows that differ between two projections, readable ("table key: col=a vs b"), at most [max]. */
fun diffRows(a: Projection, b: Projection, max: Int = 8): List<String> {
    val out = ArrayList<String>()
    for (table in (a.keys + b.keys).sorted()) {
        val ra = a[table].orEmpty()
        val rb = b[table].orEmpty()
        for (k in (ra.keys + rb.keys).sorted()) {
            val x = ra[k]
            val y = rb[k]
            if (x == y) continue
            val cols = ((x?.keys ?: emptySet()) + (y?.keys ?: emptySet())).filter { x?.get(it) != y?.get(it) }
            out += "$table $k: " + if (x == null || y == null) "${x ?: "absent"} vs ${y ?: "absent"}" else cols.joinToString { "$it=${x[it]} vs ${y[it]}" }
            if (out.size >= max) return out
        }
    }
    return out
}

/** SYNC.md 9.3: a card's cache is maintained iff it is live and not a merge loser (its note may be tombstoned). */
fun isMaintained(card: Row): Boolean = card.isNull("deleted_at") && card.isNull("merged_into_id")

/** A fresh replica (built-ins seeded) that applied [log] in seq order with the device apply function. */
fun replicaOf(log: List<Op>): InMemoryLearningTables {
    val applier = DeviceOpApplier<InMemoryLearningTables>()
    return InMemoryLearningTables.seeded().also { t -> for (op in log) if (op.opType.deviceApplies) applier.applyTo(t, op) }
}

/** [t] with every maintained card cache recomputed by a full replay (SYNC.md 9.3): differs from [t] iff a cache is stale. */
fun fullyReplayed(t: InMemoryLearningTables): InMemoryLearningTables = t.copy().also { DeviceCardCache().replayAll(it) }
