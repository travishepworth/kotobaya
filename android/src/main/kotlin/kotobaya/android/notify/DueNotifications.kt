package kotobaya.android.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotobaya.android.R
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.studyDayOf
import kotobaya.db.app.AppDatabase
import java.time.Instant
import java.time.LocalTime

/**
 * The notification side of [DueListener]: refreshes the permanent due-count notification, posts nudges and the
 * stale-sync event, then re-plans the single alarm. Posting is a no-op when notifications are not allowed (the alarm
 * is still planned so a later grant and the other listeners keep working).
 */
class DueNotifications(
    private val context: Context,
    private val db: AppDatabase,
    private val loader: LocalQueueLoader,
    private val prefs: NotifyPrefs,
    private val signals: NotifySignals,
    private val scheduler: DueScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notifier: Notifier = SystemNotifier(context),
) : DueListener {

    /** Posting seam: tests record, production uses NotificationManagerCompat. */
    interface Notifier {
        fun canPost(): Boolean
        fun post(id: Int, notification: Notification)
    }

    override fun onDueChanged() {
        val now = clock()
        val settings = QueueSettings.from(
            db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ },
        )
        val day = studyDayOf(now, settings.timezone, settings.dayRolloverHour).day
        val counts = loader.counts()
        val due = counts.due + counts.learning
        val lastSync = signals.lastSyncAtMs()
        if (notifier.canPost()) {
            Channels.ensure(context)
            notifier.post(ID_DUE, dueCountNotification(due, counts.new))
            maybeNudge(now, day.startMs, due, settings)
            maybeStale(now, lastSync)
        }
        scheduler.schedule(
            NextAlarmCalculator.next(
                dues = listOfNotNull(loader.nextDueInstant()),
                now = now,
                zone = settings.timezone,
                lastAlarm = prefs.lastAlarmMs,
                dayRolloverHour = settings.dayRolloverHour,
                eveningReminder = if (prefs.nudgesEnabled) prefs.eveningHour?.let { LocalTime.of(it, 0) } else null,
                extra = listOfNotNull(lastSync?.let { it + STALE_AFTER_MS }),
            ),
        )
    }

    /** "142 due · 38 new": due includes learning cards. */
    fun dueCountText(due: Int, new: Int): String = context.getString(R.string.notify_due_count, due, new)

    private fun dueCountNotification(due: Int, new: Int): Notification =
        NotificationCompat.Builder(context, Channels.DUE_COUNT)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(dueCountText(due, new))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(launchIntent())
            .build()

    private fun maybeNudge(now: Long, dayStart: Long, due: Int, settings: QueueSettings) {
        if (!prefs.nudgesEnabled) return
        if (prefs.nudgeThreshold > 0 && due >= prefs.nudgeThreshold && prefs.thresholdNudgeDay != dayStart) {
            prefs.thresholdNudgeDay = dayStart
            notifier.post(ID_NUDGE, nudge(context.getString(R.string.notify_nudge_threshold, due)))
        }
        val hour = prefs.eveningHour ?: return
        val localHour = Instant.ofEpochMilli(now).atZone(settings.timezone).hour
        if (localHour >= hour && prefs.eveningNudgeDay != dayStart && due > 0 && !signals.reviewedSince(dayStart)) {
            prefs.eveningNudgeDay = dayStart
            notifier.post(ID_EVENING, nudge(context.getString(R.string.notify_nudge_evening, due)))
        }
    }

    /** Once per stale episode: an episode is identified by the `last_sync_at` it started from. */
    private fun maybeStale(now: Long, lastSync: Long?) {
        if (lastSync == null || now - lastSync < STALE_AFTER_MS || prefs.staleNotifiedFor == lastSync) return
        prefs.staleNotifiedFor = lastSync
        notifier.post(
            ID_STALE,
            NotificationCompat.Builder(context, Channels.SERVER_EVENTS)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(context.getString(R.string.notify_sync_stale_title))
                .setContentText(context.getString(R.string.notify_sync_stale_text))
                .setAutoCancel(true)
                .setContentIntent(launchIntent())
                .build(),
        )
    }

    private fun nudge(text: String): Notification =
        NotificationCompat.Builder(context, Channels.NUDGES)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()

    private fun launchIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }

    companion object {
        const val ID_DUE = 1001
        const val ID_NUDGE = 1002
        const val ID_EVENING = 1003
        const val ID_STALE = 1004
        const val STALE_AFTER_MS = 2L * 24 * 60 * 60 * 1000
    }
}

class SystemNotifier(private val context: Context) : DueNotifications.Notifier {
    private val nm get() = NotificationManagerCompat.from(context)

    override fun canPost() = nm.areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission")
    override fun post(id: Int, notification: Notification) {
        if (canPost()) nm.notify(id, notification)
    }
}
