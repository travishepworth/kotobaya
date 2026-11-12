package kotobaya.android.push

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotobaya.android.notify.Channels
import kotobaya.android.notify.DueNotifications
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PushHandlerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    private class Recorder : DueNotifications.Notifier {
        var allowed = true
        val posted = mutableListOf<Pair<Int, Notification>>()
        override fun canPost() = allowed
        override fun post(id: Int, notification: Notification) {
            posted += id to notification
        }
    }

    private var syncs = 0
    private val rec = Recorder()
    private val handler get() = PushHandler(context, { syncs++ }, rec)

    private fun job(status: String, error: String? = null) =
        """{"type":"job.updated","at":"2026-11-12T09:30:00.000Z","data":{"id":"j1","kind":"media-import","status":"$status","error":${error?.let { "\"$it\"" } ?: "null"}}}"""
            .toByteArray()

    @Test
    fun aJobSucceededMessageBecomesAServerEventsNotification() {
        assertTrue(handler.onMessage(job("succeeded")))
        val (_, n) = rec.posted.single()
        assertEquals(Channels.SERVER_EVENTS, n.channelId)
        assertEquals("media-import finished", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun aFailedJobShowsItsError() {
        handler.onMessage(job("failed", "bad file"))
        val n = rec.posted.single().second
        assertEquals("media-import failed", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("bad file", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun syncChangedRequestsASyncAndPostsNothing() {
        assertTrue(handler.onMessage("""{"type":"sync.changed","at":"2026-11-12T09:30:00.000Z","data":{"high_water_seq":5}}""".toByteArray()))
        assertEquals(1, syncs)
        assertTrue(rec.posted.isEmpty())
    }

    @Test
    fun malformedOrUnknownPayloadsAreIgnored() {
        val bad = listOf(
            "", "not json", "{", "[]", "{}", """{"type":"job.updated"}""",
            """{"type":"job.updated","data":{"status":"running","id":"a","kind":"k"}}""",
            """{"type":"job.updated","data":{"status":"succeeded"}}""", """{"type":"note.created","data":{}}""",
        )
        for (b in bad) assertFalse(handler.onMessage(b.toByteArray()), b)
        assertFalse(handler.onMessage(ByteArray(20_000) { 'a'.code.toByte() }))
        assertFalse(handler.onMessage(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        assertTrue(rec.posted.isEmpty())
        assertEquals(0, syncs)
    }

    @Test
    fun noNotificationWhenNotificationsAreNotAllowed() {
        rec.allowed = false
        handler.onMessage(job("succeeded"))
        assertTrue(rec.posted.isEmpty())
    }

    @Test
    fun aThrowingSyncTriggerDoesNotBreakTheHandler() {
        val h = PushHandler(context, { error("boom") }, rec)
        assertTrue(h.onMessage("""{"type":"sync.changed","data":{}}""".toByteArray()))
    }
}
