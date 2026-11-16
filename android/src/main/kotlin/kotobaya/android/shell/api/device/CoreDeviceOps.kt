package kotobaya.android.shell.api.device

import android.content.Context
import kotobaya.android.BuildConfig
import kotobaya.android.pairing.PairResult
import kotobaya.android.pairing.PairingRepository
import kotobaya.android.pairing.PairingTarget
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.android.shell.api.ShellServices
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SyncStatusRepository
import kotobaya.android.sync.SyncTrigger
import kotobaya.android.sync.SyncTriggers
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.store.bookkeeping
import org.json.JSONObject

/**
 * What the device group's routes need (SHELL.md 4.3 "device"): the pairing, the sync status and the sync engine,
 * as one seam. Production is [CoreDeviceOps] (built from `call.services`); tests register a fake as a service.
 */
interface DeviceOps {
    /** `ShellInfo`; works unpaired. */
    fun info(): ShellInfoData

    /** `ShellSyncStatus`: [SyncStatusRepository.current] plus the network and the pull cursor. */
    fun syncStatus(): SyncStatusData

    /** `PairingRepository.pair` with the shell's rules (no new identity on `hlc_regressed`). */
    suspend fun pair(target: PairingTarget, name: String?): PairResult

    /** `PairingRepository.unpair`: the token only. */
    fun unpair()

    /** Asks the sync engine for a cycle and returns at once (`SyncTriggers`, trigger MANUAL). */
    fun startSync()
}

/** The production [DeviceOps] over the app's repositories. */
class CoreDeviceOps(
    private val pairing: PairingRepository,
    private val tokens: TokenStore,
    private val settings: SettingsStore,
    private val status: SyncStatusRepository,
    private val triggers: SyncTriggers,
    private val network: NetworkMonitor,
    private val store: SqlDelightDeviceStore,
    private val identity: ShellIdentity,
    private val webVersion: () -> String,
) : DeviceOps {
    override fun info(): ShellInfoData {
        val paired = tokens.token != null
        val deviceId = pairing.deviceId
        return ShellInfoData(
            shellVersion = BuildConfig.VERSION_NAME,
            shellVersionCode = BuildConfig.VERSION_CODE,
            paired = paired,
            authRequired = paired && status.current().status.phase == SyncPhase.AuthRequired,
            serverUrl = settings.serverBaseUrl,
            deviceId = deviceId,
            deviceName = if (deviceId != null) identity.deviceName() else null,
            online = network.isOnline(),
            capabilities = CAPABILITIES,
            webVersion = webVersion(),
            webSource = "bundled",
        )
    }

    override fun syncStatus(): SyncStatusData =
        SyncStatusData(status.current(), network.isOnline(), store.read { it.bookkeeping.pullSeq })

    override suspend fun pair(target: PairingTarget, name: String?): PairResult =
        pairing.pair(target, name, retryOnHlcRegressed = false)

    override fun unpair() = pairing.unpair()

    override fun startSync() = triggers.background(SyncTrigger.MANUAL)

    companion object {
        /** Features this build offers; A7 adds `dict_install`, `offline_storage`, `notifications`, `push`, `ankiconnect`. */
        val CAPABILITIES: List<String> = listOf("sync", "pairing", "takoboto")

        /** Resolves the production instance from the app's services. */
        fun from(s: ShellServices): CoreDeviceOps {
            val ctx = s.get<Context>()
            return CoreDeviceOps(
                pairing = s.get(),
                tokens = s.get(),
                settings = s.get(),
                status = s.get(),
                triggers = s.get(),
                network = s.get(),
                store = s.get(),
                identity = s.get(),
                webVersion = { bundledWebVersion(ctx) },
            )
        }

        /** `web_version` of `assets/web/shell-manifest.json`; the app's own version until that file exists. */
        internal fun bundledWebVersion(ctx: Context): String = runCatching {
            ctx.assets.open("web/shell-manifest.json").use { JSONObject(it.readBytes().decodeToString()).optString("web_version") }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: BuildConfig.VERSION_NAME
    }
}
