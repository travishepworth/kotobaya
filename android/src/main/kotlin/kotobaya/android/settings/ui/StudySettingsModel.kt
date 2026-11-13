package kotobaya.android.settings.ui

import kotobaya.android.queue.QueueSettings
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult

/** The three study settings this screen edits (SCHEMA.md 6). */
enum class StudyKey(val key: String, val min: Long, val max: Long) {
    NEW_PER_DAY("scheduler.new_per_day", 0, Int.MAX_VALUE.toLong()),
    REVIEWS_PER_DAY("scheduler.reviews_per_day", 0, Int.MAX_VALUE.toLong()),
    ROLLOVER_HOUR("scheduler.day_rollover_hour", 0, 23),
}

sealed interface StudyResult {
    data object Saved : StudyResult
    data object Unchanged : StudyResult
    data object OutOfRange : StudyResult
    data class Refused(val reason: MintRefusal) : StudyResult
}

/**
 * Reads the study settings from the replica and changes them only by minting `settings.set` ops (one per change).
 * [readRaw] is `settings.key` to stored JSON text; [mint] is the app's OpMinter.
 */
class StudySettingsModel(
    private val readRaw: () -> Map<String, String>,
    private val mint: (OpType, JsonObject) -> MintResult,
    private val onMinted: () -> Unit = {},
) {
    private fun current(): QueueSettings = QueueSettings.from(readRaw())

    fun valueOf(key: StudyKey): Int = current().let {
        when (key) {
            StudyKey.NEW_PER_DAY -> it.newPerDay
            StudyKey.REVIEWS_PER_DAY -> it.reviewsPerDay
            StudyKey.ROLLOVER_HOUR -> it.dayRolloverHour
        }
    }

    /** An out-of-range [value] mints nothing; an unchanged one too. */
    fun set(key: StudyKey, value: Long): StudyResult {
        if (value < key.min || value > key.max) return StudyResult.OutOfRange
        if (valueOf(key).toLong() == value) return StudyResult.Unchanged
        val payload = JsonObject(linkedMapOf<String, JsonValue>("key" to JsonString(key.key), "value" to JsonNumber(value.toDouble())))
        return when (val r = mint(OpType.SETTINGS_SET, payload)) {
            is MintResult.Minted -> { onMinted(); StudyResult.Saved }
            is MintResult.Refused -> StudyResult.Refused(r.reason)
        }
    }
}
