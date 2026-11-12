package kotobaya.android.notify

import android.content.Context
import android.content.SharedPreferences

/** Device-local notification settings and bookkeeping. Nudges are OFF by default. */
class NotifyPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("kotobaya-notify", Context.MODE_PRIVATE))

    var nudgesEnabled: Boolean
        get() = prefs.getBoolean("nudges_enabled", false)
        set(v) = prefs.edit().putBoolean("nudges_enabled", v).apply()

    /** Nudge when at least this many cards are due. 0 turns the threshold nudge off. */
    var nudgeThreshold: Int
        get() = prefs.getInt("nudge_threshold", 50)
        set(v) = prefs.edit().putInt("nudge_threshold", v).apply()

    /** Evening reminder (if nothing was reviewed today) at this local hour; null = off. */
    var eveningHour: Int?
        get() = prefs.getInt("evening_hour", -1).takeIf { it in 0..23 }
        set(v) = prefs.edit().putInt("evening_hour", v ?: -1).apply()

    var lastAlarmMs: Long?
        get() = if (prefs.contains("last_alarm")) prefs.getLong("last_alarm", 0) else null
        set(v) = prefs.edit().apply { if (v == null) remove("last_alarm") else putLong("last_alarm", v) }.apply()

    /** Study-day start of the last threshold nudge / evening nudge, so each fires once per study day. */
    var thresholdNudgeDay: Long
        get() = prefs.getLong("nudge_threshold_day", Long.MIN_VALUE)
        set(v) = prefs.edit().putLong("nudge_threshold_day", v).apply()

    var eveningNudgeDay: Long
        get() = prefs.getLong("nudge_evening_day", Long.MIN_VALUE)
        set(v) = prefs.edit().putLong("nudge_evening_day", v).apply()

    /** The `last_sync_at` value the stale notification was already posted for (one per stale episode). */
    var staleNotifiedFor: Long?
        get() = if (prefs.contains("stale_for")) prefs.getLong("stale_for", 0) else null
        set(v) = prefs.edit().apply { if (v == null) remove("stale_for") else putLong("stale_for", v) }.apply()
}
