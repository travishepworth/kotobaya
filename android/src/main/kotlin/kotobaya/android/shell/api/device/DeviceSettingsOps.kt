package kotobaya.android.shell.api.device

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotobaya.android.ankiconnect.AnkiConnectController
import kotobaya.android.ankiconnect.AnkiConnectPrefs
import kotobaya.android.ankiconnect.AnkiConnectStatus
import kotobaya.android.dict.DictGapCache
import kotobaya.android.dict.DictSettingsPort
import kotobaya.android.dict.GapFillCounts
import kotobaya.android.dict.InstallResult
import kotobaya.android.dict.InstallState
import kotobaya.android.media.MediaSettingsPort
import kotobaya.android.media.StorageBreakdown
import kotobaya.android.notify.NotifyPrefs
import kotobaya.android.push.EnableResult
import kotobaya.android.push.PushPrefs
import kotobaya.android.push.PushRegistrar
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.get
import kotobaya.android.sync.NetworkMonitor
import org.unifiedpush.android.connector.UnifiedPush

// ---------------------------------------------------------------------------------------------- dictionary

/** `ShellDictInstall` before it is encoded. */
data class DictInstallData(
    val installedReleaseId: Long?,
    val installedScope: String?,
    val installedBytes: Long,
    val scope: String,
    val state: String,
    val doneBytes: Long,
    val totalBytes: Long,
    val lastResult: String?,
    val lastError: String?,
    val serverReleaseId: Long?,
    val gapFill: GapFillCounts?,
)

/** The dictionary install seam of `getDictInstall` / `startDictInstall` (production: [CoreDictOps]). */
interface DictOps {
    fun state(): DictInstallData

    fun isOnline(): Boolean

    /** Stores [scope] when given, then starts an install unless one is running (then nothing starts). */
    fun start(scope: String?)

    /** Emits when any property of [state] may have changed (the `shell.dict` event source). */
    fun changes(): Flow<Unit> = emptyFlow()
}

/** [DictOps] over the Settings seam [DictSettingsPort] (the one the Compose section used). */
class CoreDictOps(
    private val port: DictSettingsPort,
    private val gapFill: () -> GapFillCounts?,
    private val serverRelease: () -> Long?,
    private val online: () -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DictOps {
    private val running = AtomicBoolean(false)

    @Volatile
    private var last: InstallResult? = null

    override fun isOnline(): Boolean = online()

    override fun state(): DictInstallData {
        val s = port.installState.value
        val info = port.info()
        if (s is InstallState.Finished) last = s.result
        val stage = when {
            s is InstallState.Running -> s.stage.name.lowercase()
            running.get() -> "checking"
            else -> "idle"
        }
        val result = last
        return DictInstallData(
            installedReleaseId = info?.releaseId,
            installedScope = info?.scope,
            installedBytes = if (info != null) port.installedBytes() else 0L,
            scope = port.scope(),
            state = stage,
            doneBytes = (s as? InstallState.Running)?.doneBytes ?: 0L,
            totalBytes = (s as? InstallState.Running)?.totalBytes ?: 0L,
            lastResult = result?.let(::resultName),
            lastError = result?.let(::resultError),
            serverReleaseId = serverRelease(),
            gapFill = gapFill(),
        )
    }

    override fun start(scope: String?) {
        if (scope != null) port.setScope(scope)
        if (!running.compareAndSet(false, true)) return
        this.scope.launch(io) {
            try {
                port.startup()
                port.runUpdate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = InstallResult.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                (port.installState.value as? InstallState.Finished)?.let { last = it.result }
                running.set(false)
            }
        }
    }

    override fun changes(): Flow<Unit> = merge(port.installState.map { }, port.generation.map { })

    companion object {
        fun resultName(r: InstallResult): String = when (r) {
            InstallResult.NoRelease -> "no_release"
            InstallResult.UpToDate -> "up_to_date"
            InstallResult.DowngradeIgnored -> "downgrade_ignored"
            is InstallResult.Installed -> "installed"
            InstallResult.NeedsNewerApp -> "needs_newer_app"
            is InstallResult.InsufficientSpace -> "insufficient_space"
            is InstallResult.Failed -> "failed"
        }

        fun resultError(r: InstallResult): String? = when (r) {
            is InstallResult.InsufficientSpace -> "Needs ${r.requiredBytes} bytes free, ${r.availableBytes} available."
            is InstallResult.Failed -> r.reason.take(MAX_ERROR)
            else -> null
        }

        private const val MAX_ERROR = 500

        fun from(s: ShellServices, scope: CoroutineScope): CoreDictOps {
            val cache = s.get<DictGapCache>()
            val network = s.get<NetworkMonitor>()
            val ctx = s.get<Context>().applicationContext
            return CoreDictOps(
                port = kotobaya.android.dict.AppDictSettingsPort(
                    s.get(), s.get(), s.get(), s.get(), ensurePeriodic = { kotobaya.android.dict.DictWork.ensurePeriodic(ctx) },
                ),
                gapFill = { cache.counts() },
                serverRelease = { cache.serverReleaseId() },
                online = network::isOnline,
                scope = scope,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- storage

/** `ShellStorage` before it is encoded. */
data class StorageData(val breakdown: StorageBreakdown, val prefetchDays: Int, val budgetMb: Int, val allowMetered: Boolean)

/** `getStorage` / `updateStorage` (production: [CoreStorageOps] over [MediaSettingsPort]). */
interface StorageOps {
    fun state(): StorageData

    /** Stores the given preferences; [clearCache] deletes the cached blobs after them. */
    fun update(prefetchDays: Int?, budgetMb: Int?, allowMetered: Boolean?, clearCache: Boolean)
}

class CoreStorageOps(private val port: MediaSettingsPort) : StorageOps {
    override fun state() = StorageData(port.breakdown(), port.prefetchDays, port.budgetMb, port.allowMetered)

    override fun update(prefetchDays: Int?, budgetMb: Int?, allowMetered: Boolean?, clearCache: Boolean) {
        prefetchDays?.let { port.prefetchDays = it }
        budgetMb?.let { port.budgetMb = it }
        allowMetered?.let { port.allowMetered = it }
        if (clearCache) port.clearCache()
    }

    companion object {
        fun from(s: ShellServices): CoreStorageOps =
            CoreStorageOps(kotobaya.android.media.AppMediaSettingsPort(s.get(), s.get(), s.get()))
    }
}

// ---------------------------------------------------------------------------------------------- notifications

/** `ShellNotificationState` before it is encoded. */
data class NotifyData(
    val permission: String,
    val canRequest: Boolean,
    val nudgesEnabled: Boolean,
    val nudgeThreshold: Int,
    val eveningHour: Int?,
)

/** Notification preferences and the permission (production: [CoreNotifyOps]). */
interface NotifyOps {
    fun state(): NotifyData

    /** Sets what is not null; [setEvening] says `eveningHour` was sent (null then means "off"). */
    fun update(nudgesEnabled: Boolean?, nudgeThreshold: Int?, setEvening: Boolean, eveningHour: Int?)

    /** Shows the system dialog when needed; the state after the user chose (or the limit). */
    suspend fun requestPermission(): NotifyData
}

/** The system side of the permission, for tests. */
interface NotificationSystem {
    val sdkInt: Int

    /** `POST_NOTIFICATIONS` granted (API 33+) / notifications enabled for the app. */
    fun isGranted(): Boolean

    /**
     * Launches the system dialog through the activity-result launcher MainActivity registered
     * ([NotificationPermissionLauncher]); resumes with the answer, or null when there is no activity.
     */
    suspend fun launchDialog(): Boolean?
}

/** Registered by MainActivity ([register]); the shell's permission request goes through it. */
object NotificationPermissionLauncher {
    @Volatile
    private var launch: (((Boolean) -> Unit) -> Unit)? = null

    @Volatile
    private var pending: ((Boolean) -> Unit)? = null

    /** Call from `MainActivity.onCreate`. */
    fun register(activity: androidx.activity.ComponentActivity) {
        val l = activity.registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            pending?.invoke(granted)
            pending = null
        }
        launch = { cb ->
            pending = cb
            l.launch("android.permission.POST_NOTIFICATIONS")
        }
        activity.lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    launch = null
                    pending = null
                }
            },
        )
    }

    internal fun launcher(): (((Boolean) -> Unit) -> Unit)? = launch
}

class AndroidNotificationSystem(private val context: Context) : NotificationSystem {
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun isGranted(): Boolean =
        if (sdkInt >= 33) {
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    override suspend fun launchDialog(): Boolean? {
        val launch = NotificationPermissionLauncher.launcher() ?: return null
        return suspendCancellableCoroutine { cont ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { launch { granted -> if (cont.isActive) cont.resumeWith(Result.success(granted)) } }
                    .onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            }
        }
    }
}

/** How many times the dialog was answered "no" (device-local; two denials end the dialog). */
interface DenialCounter {
    var count: Int
}

class CoreNotifyOps(
    private val prefs: NotifyPrefs,
    private val system: NotificationSystem,
    private val denials: DenialCounter,
    private val timeoutMs: Long = DIALOG_TIMEOUT_MS,
) : NotifyOps {
    override fun state(): NotifyData {
        val granted = system.isGranted()
        val permission = when {
            granted -> "granted"
            system.sdkInt < 33 -> "denied"
            denials.count == 0 -> "not_requested"
            else -> "denied"
        }
        return NotifyData(
            permission = permission,
            canRequest = !granted && system.sdkInt >= 33 && denials.count < 2,
            nudgesEnabled = prefs.nudgesEnabled,
            nudgeThreshold = prefs.nudgeThreshold,
            eveningHour = prefs.eveningHour,
        )
    }

    override fun update(nudgesEnabled: Boolean?, nudgeThreshold: Int?, setEvening: Boolean, eveningHour: Int?) {
        nudgesEnabled?.let { prefs.nudgesEnabled = it }
        nudgeThreshold?.let { prefs.nudgeThreshold = it }
        if (setEvening) prefs.eveningHour = eveningHour
    }

    override suspend fun requestPermission(): NotifyData {
        val s = state()
        if (system.sdkInt < 33 || system.isGranted() || !s.canRequest) return s
        val answer = withTimeoutOrNull(timeoutMs) { system.launchDialog() }
        if (answer == false) denials.count += 1
        return state()
    }

    companion object {
        const val DIALOG_TIMEOUT_MS: Long = 25_000

        fun from(s: ShellServices): CoreNotifyOps {
            val ctx = s.get<Context>()
            val p = ctx.getSharedPreferences("kotobaya-shell-notify", Context.MODE_PRIVATE)
            return CoreNotifyOps(
                s.get(), AndroidNotificationSystem(ctx),
                object : DenialCounter {
                    override var count: Int
                        get() = p.getInt("denials", 0)
                        set(v) = p.edit().putInt("denials", v).apply()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------- push

/** `ShellPushState` before it is encoded; [state] is `off`, `no_distributor`, `waiting` or `on`. */
data class PushData(val state: String, val hasEndpoint: Boolean)

interface PushOps {
    fun state(): PushData

    /** `enabled: true` registers (again); `false` unregisters. */
    fun update(enabled: Boolean): PushData
}

class CorePushOps(
    private val prefs: PushPrefs,
    private val registrar: PushRegistrar,
    /** A distributor is saved: registration was requested and not undone. */
    private val registered: () -> Boolean,
) : PushOps {
    @Volatile
    private var noDistributor = false

    override fun state(): PushData {
        val hasEndpoint = prefs.endpoint != null
        val state = when {
            hasEndpoint -> "on"
            noDistributor -> "no_distributor"
            registered() -> "waiting"
            else -> "off"
        }
        return PushData(state, hasEndpoint)
    }

    override fun update(enabled: Boolean): PushData {
        if (enabled) {
            when (registrar.enable()) {
                EnableResult.NoDistributor -> noDistributor = true
                EnableResult.Requested -> {
                    noDistributor = false
                    registrar.retryServerRegistration()
                }
            }
        } else {
            noDistributor = false
            registrar.disable()
        }
        return state()
    }

    companion object {
        fun from(s: ShellServices): CorePushOps {
            val ctx = s.get<Context>()
            return CorePushOps(s.get(), s.get()) { runCatching { UnifiedPush.getSavedDistributor(ctx) != null }.getOrDefault(false) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- AnkiConnect

/** `ShellAnkiConnectState` before it is encoded. */
data class AnkiConnectData(
    val enabled: Boolean,
    val port: Int,
    val apiKey: String,
    val origins: List<String>,
    val status: String,
    val statusMessage: String?,
)

interface AnkiConnectOps {
    fun state(): AnkiConnectData

    /** Stores the given settings, restarts the server and answers with the state once it settled. */
    suspend fun update(enabled: Boolean?, port: Int?, apiKey: String?, origins: List<String>?): AnkiConnectData
}

/** The server side of [CoreAnkiConnectOps], for tests. */
interface AnkiConnectEngine {
    val status: StateFlow<AnkiConnectStatus>

    /** Starts, restarts or stops the server to match the stored settings (may finish later). */
    fun apply()
}

class CoreAnkiConnectOps(
    private val prefs: AnkiConnectPrefs,
    private val engine: AnkiConnectEngine,
    private val settleMs: Long = SETTLE_MS,
) : AnkiConnectOps {
    override fun state(): AnkiConnectData {
        val st = engine.status.value
        return AnkiConnectData(
            enabled = prefs.enabled,
            port = prefs.port,
            apiKey = prefs.apiKey,
            // `[]` is the built-in default list
            origins = if (prefs.origins == AnkiConnectPrefs.DEFAULT_ORIGINS) emptyList() else prefs.originList(),
            status = when (st) {
                is AnkiConnectStatus.Running -> "running"
                is AnkiConnectStatus.Failed -> "failed"
                AnkiConnectStatus.Stopped -> "stopped"
            },
            statusMessage = (st as? AnkiConnectStatus.Failed)?.message?.take(500),
        )
    }

    override suspend fun update(enabled: Boolean?, port: Int?, apiKey: String?, origins: List<String>?): AnkiConnectData {
        enabled?.let { prefs.enabled = it }
        port?.let { prefs.port = it }
        apiKey?.let { prefs.apiKey = it }
        origins?.let { prefs.origins = if (it.isEmpty()) AnkiConnectPrefs.DEFAULT_ORIGINS else it.joinToString(",") }
        engine.apply()
        withTimeoutOrNull(settleMs) {
            engine.status.first { st ->
                if (prefs.enabled) {
                    st is AnkiConnectStatus.Failed || (st is AnkiConnectStatus.Running && st.port == prefs.port)
                } else {
                    st is AnkiConnectStatus.Stopped
                }
            }
        }
        return state()
    }

    companion object {
        const val SETTLE_MS: Long = 3_000

        fun from(s: ShellServices): CoreAnkiConnectOps {
            val controller = s.get<AnkiConnectController>()
            val ctx = s.get<Context>()
            return CoreAnkiConnectOps(
                s.get(),
                object : AnkiConnectEngine {
                    override val status: StateFlow<AnkiConnectStatus> get() = controller.status

                    override fun apply() = controller.apply(ctx)
                },
            )
        }
    }
}
