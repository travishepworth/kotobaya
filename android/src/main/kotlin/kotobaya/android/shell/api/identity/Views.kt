package kotobaya.android.shell.api.identity

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** An API `Timestamp`: UTC, millisecond precision, as the server writes it. */
internal fun timestamp(ms: Long): String = TIMESTAMP.format(java.time.Instant.ofEpochMilli(ms))

internal fun obj(vararg members: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*members))

private fun Row.ms(column: String): Long? = (this[column] as? JsonNumber)?.value?.toLong()

private fun Row.col(column: String): JsonValue = this[column] ?: JsonNull

/** `Timestamp` of an epoch-ms column, or JSON null. */
private fun Row.stamp(column: String): JsonValue = ms(column)?.let { JsonString(timestamp(it)) } ?: JsonNull

internal fun Row.isDeleted(): Boolean = col("deleted_at") != JsonNull

private fun Row.strOf(column: String): String = (this[column] as? JsonString)?.value.orEmpty()

private fun Row.number(column: String): Double = (this[column] as? JsonNumber)?.value ?: 0.0

/** Settings whose value is not JSON null, ordered by key (`listSettings`). */
internal fun settingList(tables: LearningTables): JsonObject {
    val rows = tables.all(LearningTable.SETTINGS)
        .filter { it.col("value") != JsonNull }
        .sortedBy { it.strOf("key") }
        .map { r -> obj("key" to r.col("key"), "value" to r.col("value"), "updated_at" to JsonString(timestamp(r.ms("updated_at") ?: 0L))) }
    return obj("items" to JsonArray(rows))
}

/** `Preset` from an `fsrs_params` row. */
internal fun presetView(r: Row): JsonObject = obj(
    "id" to r.col("id"),
    "name" to r.col("name"),
    "desired_retention" to r.col("desired_retention"),
    "maximum_interval_days" to r.col("maximum_interval_days"),
    "learning_steps_s" to r.col("learning_steps_s"),
    "relearning_steps_s" to r.col("relearning_steps_s"),
    "fuzz" to (r.col("fuzz") as? JsonBool ?: JsonBool(false)),
    "easy_days" to r.col("easy_days"),
    "fsrs_version" to r.col("fsrs_version"),
    "weights" to r.col("weights"),
    "weights_version" to r.col("weights_version"),
    "optimized_at" to r.stamp("optimized_at"),
    "optimizer_stats" to r.col("optimizer_stats"),
    "created_at" to r.stamp("created_at"),
    "updated_at" to r.stamp("updated_at"),
    "deleted_at" to r.stamp("deleted_at"),
)

/** Presets ordered by `created_at`, then `id` (the Default preset first). */
internal fun presetRows(tables: LearningTables): List<Row> =
    tables.all(LearningTable.FSRS_PARAMS).sortedWith(compareBy<Row> { it.number("created_at") }.thenBy { it.strOf("id") })

/** `NoteType` from a `note_type` row. */
internal fun noteTypeView(r: Row): JsonObject = obj(
    "id" to r.col("id"),
    "name" to r.col("name"),
    "base_type" to r.col("base_type"),
    "builtin" to (r.col("builtin") as? JsonBool ?: JsonBool(false)),
    "fields" to r.col("fields"),
    "templates" to r.col("templates"),
    "css" to r.col("css"),
    "created_at" to r.stamp("created_at"),
    "updated_at" to r.stamp("updated_at"),
    "deleted_at" to r.stamp("deleted_at"),
)

/** Note types ordered by `builtin` descending, then `created_at`, then `id`. */
internal fun noteTypeRows(tables: LearningTables): List<Row> =
    tables.all(LearningTable.NOTE_TYPE).sortedWith(
        compareByDescending<Row> { (it.col("builtin") as? JsonBool)?.value == true }
            .thenBy { it.number("created_at") }
            .thenBy { it.strOf("id") },
    )
