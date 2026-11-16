package kotobaya.android.shell.api.identity

import android.os.Build
import kotobaya.android.dict.DictStore
import kotobaya.android.net.MetaLimits
import kotobaya.android.net.MetaLimitsStore
import kotobaya.android.sync.SqlDelightDeviceStore

/** The installed dictionary release as `getMeta` reports it (`dict_release`). */
data class DictReleaseRef(val id: Long, val version: String)

/**
 * What the identity handlers need besides the replica (SHELL.md 4.3): the stored pairing, the last `/meta`
 * the app read from the server, the app's own versions and the device clock. A service of `call.services`.
 */
interface ShellIdentity {
    /** The device name given at pairing. */
    fun deviceName(): String

    /** `PairResponse.token_id`, or null when this install paired before it was kept. */
    fun tokenId(): String?

    /** `server_version` of the last `/meta` read from the server; null before the first. */
    fun serverVersion(): String?

    /** The app's shared core version. */
    fun coreVersion(): String

    /** The installed dictionary package, or null. */
    fun dictRelease(): DictReleaseRef?

    /** The last fetched `/meta` limits, else the contract defaults. */
    fun limits(): MetaLimits

    /** The device clock, epoch ms. */
    fun nowMs(): Long
}

/** Device-local `sync_state` keys the identity reads (not contract keys, never synced). */
object IdentityKeys {
    /** `token_id` of the `PairResponse`. */
    const val TOKEN_ID: String = "pairing.token_id"

    /** The device name sent at pairing. */
    const val DEVICE_NAME: String = "pairing.device_name"

    /** `server_version` of the last `/meta`. */
    const val SERVER_VERSION: String = "meta.server_version"
}

/** The production [ShellIdentity]: `sync_state`, [MetaLimitsStore] and the installed [DictStore]. */
class AppShellIdentity(
    private val store: SqlDelightDeviceStore,
    private val limits: MetaLimitsStore,
    private val dict: DictStore,
    private val fallbackName: String = Build.MODEL.orEmpty().ifBlank { "Android" },
    private val now: () -> Long = System::currentTimeMillis,
) : ShellIdentity {
    private fun key(k: String): String? = store.read { it.syncState.get(k) }?.takeIf { it.isNotEmpty() }

    override fun deviceName(): String = key(IdentityKeys.DEVICE_NAME) ?: fallbackName

    override fun tokenId(): String? = key(IdentityKeys.TOKEN_ID)

    override fun serverVersion(): String? = key(IdentityKeys.SERVER_VERSION)

    override fun coreVersion(): String = kotobaya.core.coreVersion()

    override fun dictRelease(): DictReleaseRef? =
        runCatching { dict.info() }.getOrNull()?.let { DictReleaseRef(it.releaseId, it.version) }

    override fun limits(): MetaLimits = limits.current()

    override fun nowMs(): Long = now()
}
