package kotobaya.android.sync

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotobaya.android.net.Reachability
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.FailureKind
import kotobaya.sync.client.protocol.SyncStep
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WorkManager scheduling (`work-testing`): unique periodic and one-shot work; failed runs retried per T303a's backoff. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SyncWorkTest {
    private lateinit var ctx: Context
    private lateinit var wm: WorkManager
    // the Android driver (Robolectric's SQLite): JDBC's DriverManager is unreliable inside Robolectric's class loader
    private val store by lazy { SqlDelightDeviceStore(AndroidSqliteDriver(AppDatabase.Schema, ctx, null)) }
    private val counter by lazy { StoreFailureCounter(store) }
    private var result: CycleResult = CycleResult.Completed(0, 0, 0)
    private var cycles = 0

    private val coordinator by lazy {
        SyncCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            runCycle = {
                cycles++
                result
            },
            isPaired = { true },
            isOnline = { true },
            probe = { Reachability.Reachable(authenticated = true) },
            counter = counter,
            jitter = { 0.0 },
            onOutcome = { _, outcome, delay -> SyncWork.applyOutcome(wm, outcome, delay) },
        )
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                SyncWorker(appContext, workerParameters) { coordinator }
        }
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(factory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
        wm = WorkManager.getInstance(ctx)
    }

    private fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    private fun live(name: String): List<WorkInfo> = infos(name).filter { !it.state.isFinished }

    private fun runToEnd(id: UUID, delayed: Boolean = false) {
        val driver = WorkManagerTestInitHelper.getTestDriver(ctx)!!
        if (delayed) driver.setInitialDelayMet(id)
        driver.setAllConstraintsMet(id)
        val deadline = System.currentTimeMillis() + 5_000
        // a retry run that succeeds cancels (and so finishes) its own unique work: null = pruned
        while (wm.getWorkInfoById(id).get()?.state?.isFinished == false) {
            check(System.currentTimeMillis() < deadline) { "work $id did not finish" }
            Thread.sleep(10)
        }
    }

    @Test
    fun periodicAndOneShotAreEnqueuedUniquely() {
        SyncWork.ensurePeriodic(wm)
        SyncWork.ensurePeriodic(wm)
        val periodic = live(SyncWork.PERIODIC)
        assertEquals(1, periodic.size)
        assertTrue(periodic.single().periodicityInfo!!.repeatIntervalMillis == SyncWork.PERIOD_MINUTES * 60_000)
        assertTrue(periodic.single().constraints.requiredNetworkType == androidx.work.NetworkType.CONNECTED)

        SyncWork.requestNow(wm)
        SyncWork.requestNow(wm)
        val now = live(SyncWork.NOW)
        // one runnable; a second request is chained behind it, never run in parallel
        assertEquals(1, now.count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(1, now.count { it.state == WorkInfo.State.BLOCKED })
        // still exactly one periodic
        assertEquals(1, live(SyncWork.PERIODIC).size)
    }

    @Test
    fun aFailedRunIsRetriedPerPolicyAndASuccessCancelsTheRetry() {
        result = CycleResult.Failed(FailureKind.SERVER_ERROR, SyncStep.PUSH, "503", 503)
        SyncWork.requestNow(wm)
        runToEnd(live(SyncWork.NOW).single().id)
        assertEquals(1, cycles)
        val first = live(SyncWork.RETRY).single()
        assertEquals(5_000L, first.initialDelayMillis)

        runToEnd(first.id, delayed = true)
        assertEquals(2, cycles)
        val second = live(SyncWork.RETRY).single()
        assertEquals(10_000L, second.initialDelayMillis, "doubled")

        result = CycleResult.Completed(0, 0, 0)
        runToEnd(second.id, delayed = true)
        assertEquals(3, cycles)
        assertEquals(emptyList(), live(SyncWork.RETRY), "a success leaves no retry")
        assertEquals(0, counter.failures)
    }

    @Test
    fun theDurableDriverIsWalWithSynchronousFull() {
        val driver = DurableDriver.open(ctx)
        try {
            assertEquals("wal", DurableDriver.journalMode(driver))
            assertEquals(2L, DurableDriver.synchronousLevel(driver))
        } finally {
            driver.close()
        }
    }
}
