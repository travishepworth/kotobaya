package kotobaya.android.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Owns the one alarm. Inexact `setAndAllowWhileIdle` (no exact-alarm permission on any Android version); a new call
 * replaces the previous pending alarm, null cancels.
 */
class DueScheduler(private val context: Context, private val prefs: NotifyPrefs) {
    private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(DueReceiver.ACTION_ALARM).setClass(context, DueReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(atMs: Long?) {
        if (atMs == null) alarms.cancel(pending())
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC, atMs, pending())
    }

    fun recordFired(nowMs: Long) {
        prefs.lastAlarmMs = nowMs
    }
}
