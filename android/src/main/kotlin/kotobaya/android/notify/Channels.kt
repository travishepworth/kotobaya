package kotobaya.android.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotobaya.android.R

object Channels {
    const val DUE_COUNT = "due_count"
    const val NUDGES = "nudges"
    const val SERVER_EVENTS = "server_events"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(DUE_COUNT, context.getString(R.string.notify_channel_due), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notify_channel_due_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(NUDGES, context.getString(R.string.notify_channel_nudges), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notify_channel_nudges_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(SERVER_EVENTS, context.getString(R.string.notify_channel_server), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notify_channel_server_desc)
            },
        )
    }
}
