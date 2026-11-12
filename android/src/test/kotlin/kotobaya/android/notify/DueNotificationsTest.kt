package kotobaya.android.notify

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotobaya.android.di.appModule
import kotobaya.android.queue.queueFeature
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.db.app.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DueNotificationsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L

    @Before
    fun start() {
        stopKoin()
        startKoin {
            androidContext(context)
            modules(appModule, queueFeature.module!!, notifyModule)
        }
    }

    @After
    fun stop() = stopKoin()

    private class Recorder : DueNotifications.Notifier {
        var allowed = true
        val posted = mutableListOf<Pair<Int, Notification>>()
        override fun canPost() = allowed
        override fun post(id: Int, notification: Notification) {
            posted += id to notification
        }
    }

    private class Signals(var lastSync: Long?) : NotifySignals {
        override fun lastSyncAtMs() = lastSync
        override fun reviewedSince(sinceMs: Long) = false
    }

    private fun build(rec: Recorder, signals: NotifySignals, prefs: NotifyPrefs = NotifyPrefs(context)): DueNotifications {
        // The app's own Android-SQLite database; a JDBC driver inside a Robolectric sandbox breaks other tests.
        val db = GlobalContext.get().get<AppDatabase>()
        return DueNotifications(context, db, LocalQueueLoader(db, { now }), prefs, signals, DueScheduler(context, prefs), { now }, rec)
    }

    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test
    fun dueCountTextMatchesTheCountsAndNudgesAreOffByDefault() {
        val rec = Recorder()
        val n = build(rec, Signals(null))
        assertFalse(NotifyPrefs(context).nudgesEnabled)
        n.onDueChanged()
        assertEquals(listOf(DueNotifications.ID_DUE), rec.posted.map { it.first })
        assertEquals("0 due · 0 new", title(rec.posted.single().second))
        assertEquals("142 due · 38 new", n.dueCountText(142, 38))
    }

    @Test
    fun deniedPermissionPostsNothingButStillSchedules() {
        val rec = Recorder().also { it.allowed = false }
        build(rec, Signals(now - DueNotifications.STALE_AFTER_MS - 1)).onDueChanged()
        assertTrue(rec.posted.isEmpty())
        assertNotNull(shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextScheduledAlarm)
    }

    @Test
    fun staleSyncNotifiesOncePerEpisode() {
        val rec = Recorder()
        val signals = Signals(now - DueNotifications.STALE_AFTER_MS - 1)
        val n = build(rec, signals)
        fun stale() = rec.posted.count { it.first == DueNotifications.ID_STALE }
        n.onDueChanged()
        n.onDueChanged()
        assertEquals(1, stale())
        // a sync heals it; fresh sync -> no notification
        signals.lastSync = now - 1000
        n.onDueChanged()
        assertEquals(1, stale())
        // it goes stale again from a new last_sync_at: a second episode
        signals.lastSync = now - DueNotifications.STALE_AFTER_MS - 5000
        n.onDueChanged()
        assertEquals(2, stale())
    }

    @Test
    fun noSyncYetIsNotStale() {
        val rec = Recorder()
        build(rec, Signals(null)).onDueChanged()
        assertNull(rec.posted.firstOrNull { it.first == DueNotifications.ID_STALE })
    }

    @Test
    fun bootTimeSetAndTimeZoneChangeEachReschedule() {
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val koin = GlobalContext.get()
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            koin.get<DueScheduler>().schedule(null)
            assertNull(alarms.nextScheduledAlarm, "cleared before $action")
            DueReceiver().onReceive(context, Intent(action))
            assertNotNull(alarms.nextScheduledAlarm, "rescheduled on $action")
        }
    }

    @Test
    fun alarmActionRecordsTheFiringAndReschedulesAtLeastABatchLater() {
        val alarms = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val before = System.currentTimeMillis()
        DueReceiver().onReceive(context, Intent(DueReceiver.ACTION_ALARM))
        val last = NotifyPrefs(context).lastAlarmMs
        assertNotNull(last)
        assertTrue(last >= before)
        assertTrue(alarms.nextScheduledAlarm!!.triggerAtMs >= last + NextAlarmCalculator.BATCH_MS)
    }

    @Test
    fun fanOutReachesEveryListener() {
        var a = 0
        var b = 0
        DueTrigger { listOf(DueListener { a++ }, DueListener { error("boom") }, DueListener { b++ }) }.fire()
        assertEquals(1, a)
        assertEquals(1, b)
    }
}
