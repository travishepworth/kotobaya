package kotobaya.android.review

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Reserved
import kotobaya.contracts.cards.NoteTypeInfo
import kotobaya.contracts.cards.TemplateInfo
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.PresetRow
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

/** Readers of learning-table rows (device representation) into the core's types. */
internal object CardRows {
    fun str(v: JsonValue?): String? = (v as? JsonString)?.value

    private fun long(v: JsonValue?): Long? = (v as? JsonNumber)?.value?.toLong()

    private fun doubles(v: JsonValue?): List<Double>? = (v as? JsonArray)?.items?.map { (it as JsonNumber).value }

    fun get(t: LearningTables, table: LearningTable, id: String): Row? = t.get(table, listOf(JsonString(id)))

    fun live(r: Row): Boolean = (r["deleted_at"] ?: JsonNull) == JsonNull && (r["merged_into_id"] ?: JsonNull) == JsonNull

    fun refused(card: Row): Boolean = str(card["last_review_id"]) == Reserved.REPLAY_REFUSED_ID

    fun scheduleOf(card: Row): CardSchedule = CardSchedule(
        state = CardState.entries.firstOrNull { it.wire == str(card["state"]) } ?: CardState.NEW,
        step = long(card["step"])?.toInt(),
        stability = (card["stability"] as? JsonNumber)?.value,
        difficulty = (card["difficulty"] as? JsonNumber)?.value,
        dueAt = long(card["due_at"]),
        intervalDays = long(card["interval_days"])?.toInt(),
        lastReviewAt = long(card["last_review_at"]),
        reps = long(card["reps"])?.toInt() ?: 0,
        lapses = long(card["lapses"])?.toInt() ?: 0,
    )

    /** The effective preset (SYNC.md 9.1): the card's preset, or Default if it is deleted or absent. */
    fun presetOf(t: LearningTables, card: Row): PresetRow {
        val own = str(card["preset_id"])?.let { get(t, LearningTable.FSRS_PARAMS, it) }
        val row = own?.takeIf { (it["deleted_at"] ?: JsonNull) == JsonNull }
            ?: checkNotNull(get(t, LearningTable.FSRS_PARAMS, Reserved.DEFAULT_PRESET_ID)) { "the Default preset is missing" }
        return PresetRow(
            id = str(row["id"]) ?: "",
            desiredRetention = (row["desired_retention"] as? JsonNumber)?.value ?: 0.9,
            maximumIntervalDays = long(row["maximum_interval_days"])?.toInt() ?: 36500,
            learningStepsSeconds = doubles(row["learning_steps_s"])?.map { it.toInt() } ?: listOf(60, 600),
            relearningStepsSeconds = doubles(row["relearning_steps_s"])?.map { it.toInt() } ?: listOf(600),
            fuzz = (row["fuzz"] as? JsonBool)?.value ?: true,
            easyDays = doubles(row["easy_days"]) ?: List(7) { 1.0 },
            fsrsVersion = str(row["fsrs_version"]),
            weights = doubles(row["weights"]),
            weightsVersion = long(row["weights_version"])?.toInt() ?: 0,
            deleted = false,
        )
    }

    fun noteTypeInfo(typeRow: Row): NoteTypeInfo {
        val base = NoteBaseType.entries.firstOrNull { it.wire == str(typeRow["base_type"]) } ?: NoteBaseType.CUSTOM
        val keys = (typeRow["fields"] as? JsonArray)?.items.orEmpty().mapNotNull { str((it as? JsonObject)?.members?.get("key")) }
        val templates = (typeRow["templates"] as? JsonObject)?.members.orEmpty().map { (kind, v) ->
            val m = (v as? JsonObject)?.members.orEmpty()
            TemplateInfo(kind, (m["default"] as? JsonBool)?.value ?: false, str(m["front"]), str(m["back"]))
        }
        return NoteTypeInfo(base, keys, templates, str(typeRow["css"]).orEmpty())
    }

    /** `note.fields` as key to HTML fragment (non-string values are dropped). */
    fun fieldsOf(note: Row): Map<String, String> =
        (note["fields"] as? JsonObject)?.members.orEmpty().mapNotNull { (k, v) -> str(v)?.let { k to it } }.toMap()
}
