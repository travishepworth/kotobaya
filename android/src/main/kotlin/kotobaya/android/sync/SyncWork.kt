package kotobaya.android.sync

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotobaya.android.R
import kotobaya.android.notify.Channels
import org.koin.core.context.GlobalContext

/**
 * WorkManager scheduling of background sync (SYNC.md 6.1 "in the background while the server is
 * reachable"). All three are unique work, persisted by WorkManager across process death and reboot,
 * and need a connected network:
 *
 * - [PERIODIC]: every 15 minutes (the platform minimum), kept if already scheduled.
 * - [NOW]: an expedited one-shot after local changes (falls back to a normal one-shot when out of
 *   quota); appended after a running one so a change made during a cycle is pushed by the next.
 * - [RETRY]: the one-shot after a failed cycle, delayed by T303a's [kotobaya.sync.client.protocol.SyncBackoff]
 *   (5 s doubling to 15 min, jitter); replaced by each new failure, cancelled by a success.
 *   WorkManager's own backoff is not used (its minimum is 10 s and its cap 5 h, not the contract's).
 */
object SyncWork {
    const val PERIODIC: String = "kotobaya-sync-periodic"
    const val NOW: String = "kotobaya-sync-now"
    const val RETRY: String = "kotobaya-sync-retry"
    const val PERIOD_MINUTES: Long = 15

    private fun constraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensurePeriodic(wm: WorkManager) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun requestNow(wm: WorkManager) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints())
            .build()
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun scheduleRetry(wm: WorkManager, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .build()
        wm.enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelRetry(wm: WorkManager) {
        wm.cancelUniqueWork(RETRY)
    }
}

/**
 * Runs one sync through the [SyncCoordinator] (single flight with the in-app triggers). Always
 * succeeds as far as WorkManager is concerned: a failed cycle is retried by [SyncWork.RETRY], which
 * the coordinator's outcome handler schedules with the contract's backoff.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val coordinator: () -> SyncCoordinator?,
) : CoroutineWorker(context, params) {
    /** The constructor WorkManager's default factory uses: the coordinator comes from Koin. */
    constructor(context: Context, params: WorkerParameters) :
        this(context, params, { GlobalContext.getOrNull()?.getOrNull<SyncCoordinator>() })

    override suspend fun doWork(): Result {
        val c = coordinator() ?: return Result.retry()
        c.sync(SyncTrigger.WORKER)
        return Result.success()
    }

    /** Expedited work runs as a foreground service below API 31; this is its (silent, low) notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Channels.ensure(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Channels.SERVER_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n)
    }

    private companion object {
        const val NOTIFICATION_ID = 0x5_1A_C
    }
}
