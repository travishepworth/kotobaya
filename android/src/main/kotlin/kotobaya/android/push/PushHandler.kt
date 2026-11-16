package kotobaya.android.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotobaya.android.MainActivity
import kotobaya.android.R
import kotobaya.android.notify.Channels
import kotobaya.android.notify.DueNotifications
import org.json.JSONObject

/**
 * The seam for "the server says sync changed". T303c (sync engine) binds its trigger in Koin to replace the default
 * no-op; until then a `sync.changed` push is parsed and dropped here.
 */
fun interface PushSyncTrigger {
    fun syncRequested()
}

/** What a push message means. */
sealed class PushEvent {
    /** A job reached a terminal state: [kind] is the API job kind, [error] only when it failed. */
    data class JobDone(val jobId: String, val kind: String, val succeeded: Boolean, val error: String?) : PushEvent()

    data object SyncChanged : PushEvent()

    companion object {
        /** Messages are the server's `Event` JSON (OpenAPI `/events`); anything else is malformed and yields null. */
        fun parse(content: ByteArray): PushEvent? {
            if (content.isEmpty() || content.size > MAX_BYTES) return null
            val root = runCatching { JSONObject(String(content, Charsets.UTF_8)) }.getOrNull() ?: return null
            return when (root.optString("type")) {
                "sync.changed" -> SyncChanged
                "job.updated" -> {
                    val job = root.optJSONObject("data") ?: return null
                    val status = job.optString("status")
                    val id = job.optString("id")
                    val kind = job.optString("kind")
                    if (id.isEmpty() || kind.isEmpty() || (status != "succeeded" && status != "failed")) return null
                    JobDone(id, kind, status == "succeeded", job.optString("error").ifEmpty { null })
                }
                else -> null
            }
        }

        private const val MAX_BYTES = 16 * 1024
    }
}

/** Turns an incoming push into a Server-events notification (channel from T308) or a sync request. */
class PushHandler(
    private val context: Context,
    private val sync: PushSyncTrigger,
    private val notifier: DueNotifications.Notifier,
) {
    /** Returns true if the message was understood. Never throws: a bad push must not crash the service. */
    fun onMessage(content: ByteArray): Boolean {
        val event = PushEvent.parse(content) ?: return false
        when (event) {
            PushEvent.SyncChanged -> runCatching { sync.syncRequested() }
            is PushEvent.JobDone -> if (notifier.canPost()) {
                Channels.ensure(context)
                notifier.post(notificationId(event.jobId), jobNotification(event))
            }
        }
        return true
    }

    private fun jobNotification(e: PushEvent.JobDone) =
        NotificationCompat.Builder(context, Channels.SERVER_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(if (e.succeeded) R.string.push_job_succeeded else R.string.push_job_failed, e.kind))
            .apply { e.error?.let { setContentText(it.take(200)) } }
            .setAutoCancel(true)
            .setContentIntent(
                // SHELL-A4: an import that finished opens the media page; other jobs just open the app
                PendingIntent.getActivity(
                    context,
                    if (e.kind.contains("import")) 2 else 3,
                    if (e.kind.contains("import")) {
                        DueNotifications.shellViewIntent(context, DueNotifications.MEDIA_URI)
                    } else {
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun notificationId(jobId: String) = ID_BASE + (jobId.hashCode() and 0xFFFF)

    companion object {
        const val ID_BASE = 3000
    }
}
