package kotobaya.android.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotobaya.android.net.ApiError
import kotobaya.android.net.Reachability
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.FailureKind
import kotobaya.sync.client.protocol.SyncBackoff
import kotobaya.sync.client.protocol.SyncStep

/** What asked for a sync (SYNC.md 6.1 "Sync cycle": app open, after a review session, in the background). */
enum class SyncTrigger { APP_OPEN, REVIEW_SESSION, MANUAL, CONNECTIVITY, WORKER, PUSH }

/** The outcome of one [SyncCoordinator.sync]. */
sealed interface SyncOutcome {
    /** The device is not paired: nothing to do (not a failure). */
    data object NotPaired : SyncOutcome

    /** No network: skipped, not failed (no backoff; connectivity regained triggers the next one). */
    data object Offline : SyncOutcome

    /** The network is up but the server did not answer the reachability probe. Counts as a failure. */
    data class Unreachable(val message: String) : SyncOutcome

    /** The cycle ran (or the probe answered 401/403, reported as an AUTH failure). */
    data class Ran(val result: CycleResult) : SyncOutcome

    /** The same outcome as the [CycleResult] the T303a status derivation understands, or null. */
    fun asCycleResult(): CycleResult? = when (this) {
        NotPaired -> null
        Offline -> CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PUSH, "offline")
        is Unreachable -> CycleResult.Failed(FailureKind.SERVER_ERROR, SyncStep.PUSH, message)
        is Ran -> result
    }

    /** Whether it counts towards the backoff (SYNC.md 5.4, [SyncBackoff.isFailure]). */
    val isFailure: Boolean
        get() = when (this) {
            NotPaired, Offline -> false
            is Unreachable -> true
            is Ran -> SyncBackoff.isFailure(result)
        }
}

/** The consecutive-failure count of the backoff, kept durably so a new process continues it. */
interface FailureCounter {
    var failures: Int
}

/**
 * Runs sync cycles (SYNC.md 6.1) one at a time.
 *
 * - **Single flight:** a [sync] while one is running joins it and gets its outcome; no second cycle
 *   runs concurrently, whatever the triggers.
 * - **Startup first:** every sync waits for [ready] (the startup replay of SYNC.md 10.5).
 * - **Offline is a skip:** with no network the cycle is not attempted ([SyncOutcome.Offline]); with a
 *   network but no answer from the server ([probe]) it is [SyncOutcome.Unreachable], a failure.
 * - **Backoff:** failures ([SyncOutcome.isFailure]) increment [FailureCounter]; a cycle that ran
 *   without failing (including AUTH) resets it. [retryDelayMs] is T303a's [SyncBackoff] for the
 *   current count. [onOutcome] gets every outcome with the delay to retry after it (status, retry
 *   scheduling, due trigger).
 *
 * The cycle runs in [scope], so a caller that is cancelled (a stopped worker) does not cancel a
 * cycle other callers joined; every step of the cycle is restartable anyway (T303a).
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val runCycle: suspend () -> CycleResult,
    private val isPaired: () -> Boolean,
    private val isOnline: () -> Boolean,
    private val probe: suspend () -> Reachability,
    private val counter: FailureCounter,
    private val ready: suspend () -> Unit = {},
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble() },
    private val onOutcome: (trigger: SyncTrigger, outcome: SyncOutcome, retryDelayMs: Long) -> Unit = { _, _, _ -> },
) {
    private val lock = Mutex()
    private var inFlight: Deferred<SyncOutcome>? = null
    private val runningState = MutableStateFlow(false)

    /** True while a cycle runs. */
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    /** Number of cycles actually started (diagnostics and tests). */
    @Volatile
    var cyclesRun: Int = 0
        private set

    /** Runs a sync, or joins the one in flight. */
    suspend fun sync(trigger: SyncTrigger): SyncOutcome {
        val job = lock.withLock {
            inFlight?.takeIf { it.isActive } ?: scope.async { runOnce(trigger) }.also { inFlight = it }
        }
        return job.await()
    }

    /** Delay before retrying after the current failure count (0 after a success), T303a's policy. */
    fun retryDelayMs(): Long = SyncBackoff.delayMs(counter.failures, jitter().coerceIn(0.0, 0.999_999))

    /** Consecutive failures so far. */
    val consecutiveFailures: Int get() = counter.failures

    private suspend fun runOnce(trigger: SyncTrigger): SyncOutcome {
        ready()
        val outcome = try {
            runningState.value = true
            attempt()
        } finally {
            runningState.value = false
        }
        when {
            outcome.isFailure -> counter.failures = counter.failures + 1
            outcome is SyncOutcome.Ran -> counter.failures = 0
            else -> Unit
        }
        onOutcome(trigger, outcome, retryDelayMs())
        return outcome
    }

    private suspend fun attempt(): SyncOutcome {
        if (!isPaired()) return SyncOutcome.NotPaired
        if (!isOnline()) return SyncOutcome.Offline
        when (val r = probe()) {
            is Reachability.Down -> return when (r.error) {
                ApiError.Offline -> SyncOutcome.Offline
                else -> SyncOutcome.Unreachable(r.error.message ?: "unreachable")
            }
            is Reachability.Reachable -> if (!r.authenticated) {
                return SyncOutcome.Ran(CycleResult.Failed(FailureKind.AUTH, SyncStep.PUSH, "the server refused this device's token", 401))
            }
        }
        cyclesRun++
        return try {
            SyncOutcome.Ran(runCycle())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a local failure (SQLite, a bug in apply): nothing was committed by the failing step
            SyncOutcome.Ran(CycleResult.Failed(FailureKind.LOCAL_APPLY, SyncStep.PULL, e.message ?: e.toString()))
        }
    }
}
