package kotobaya.android.dict

import kotlinx.coroutines.CancellationException

/** What the auto-install policy reads about the device (all cheap, no network). */
interface AutoInstallEnv {
    fun paired(): Boolean

    /** `ShellSyncStatus.first_sync.done`: `sync_state.last_sync_at` is set. */
    fun firstSyncDone(): Boolean

    /** The active network is unmetered (`NET_CAPABILITY_NOT_METERED`). */
    fun unmetered(): Boolean

    /** Scope of the installed package, null when none. */
    fun installedScope(): String?

    /** The user explicitly asked for `top60k` (`startDictInstall {scope: "top60k"}`). */
    fun userChoseTop60k(): Boolean

    /** The storage budget setting, in bytes. */
    fun budgetBytes(): Long

    /** Everything counted against the budget now (blob cache and the installed dictionary). */
    fun usedBytes(): Long

    /** Size of the installed dictionary file (it is replaced by the new one, so it is not counted twice). */
    fun installedDictionaryBytes(): Long
}

/** Why [DictAutoInstall.evaluate] did nothing. */
enum class AutoInstallSkip { NOT_PAIRED, FIRST_SYNC_PENDING, ALREADY_ALL, USER_CHOSE_TOP60K, METERED, RUNNING, BACKOFF, NO_RELEASE, OVER_BUDGET }

/** The outcome of one [DictAutoInstall.evaluate]. */
sealed interface AutoInstallOutcome {
    data class Skipped(val reason: AutoInstallSkip) : AutoInstallOutcome

    /** The install ran; [result] is the last installer result (`top60k` first when nothing was installed). */
    data class Ran(val result: InstallResult) : AutoInstallOutcome
}

/**
 * Auto-install of SHELL.md L-7 "Auto-install" (ADR-053, provisional): after the first complete sync, on an unmetered
 * network, install scope `all` in the background so that a dictionary read does not depend on gap fill. Nothing is
 * downloaded on a metered network, when the user chose `top60k`, or when the result would exceed the storage budget.
 * With nothing installed `top60k` goes first (a small package, readable soon), then `all`. After a failed run no new
 * attempt starts for [retryAfterMs] (one hour). The download is the installer's (resumable). One evaluation at a time
 * is the caller's business (a unique WorkManager job); the installer serialises runs itself.
 *
 * [setScope] is what `startDictInstall` does before it runs the installer (the installer reads the scope setting);
 * [runInstall] is `DictInstaller.run`, [installRunning] is `installer.state` being `Running`.
 */
class DictAutoInstall(
    private val env: AutoInstallEnv,
    private val remote: DictRemote,
    private val installRunning: () -> Boolean,
    private val runInstall: suspend () -> InstallResult,
    private val setScope: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val failedAtMs: () -> Long? = { null },
    private val recordFailure: (Long) -> Unit = {},
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    private val log: (String) -> Unit = {},
) {
    suspend fun evaluate(): AutoInstallOutcome {
        skip()?.let { return AutoInstallOutcome.Skipped(it) }
        val release = try {
            remote.release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordFailure(nowMs())
            return AutoInstallOutcome.Skipped(AutoInstallSkip.BACKOFF)
        } ?: return AutoInstallOutcome.Skipped(AutoInstallSkip.NO_RELEASE)
        val artifact = release.artifacts.filter { it.kind == "full" && it.scope == "all" && it.formatVersion == DictSchema.USER_VERSION }
            .maxByOrNull { it.formatVersion } ?: return AutoInstallOutcome.Skipped(AutoInstallSkip.NO_RELEASE)
        val after = env.usedBytes() - env.installedDictionaryBytes() + artifact.uncompressedSizeBytes
        if (after > env.budgetBytes()) return AutoInstallOutcome.Skipped(AutoInstallSkip.OVER_BUDGET)

        if (env.installedScope() == null) {
            setScope("top60k")
            val first = runInstall()
            if (!ok(first)) return failed(first)
        }
        setScope("all")
        val result = runInstall()
        return if (ok(result)) AutoInstallOutcome.Ran(result) else failed(result)
    }

    private fun skip(): AutoInstallSkip? = when {
        !env.paired() -> AutoInstallSkip.NOT_PAIRED
        !env.firstSyncDone() -> AutoInstallSkip.FIRST_SYNC_PENDING
        env.installedScope() == "all" -> AutoInstallSkip.ALREADY_ALL
        env.userChoseTop60k() -> AutoInstallSkip.USER_CHOSE_TOP60K
        !env.unmetered() -> AutoInstallSkip.METERED
        installRunning() -> AutoInstallSkip.RUNNING
        failedAtMs()?.let { nowMs() - it < retryAfterMs } == true -> AutoInstallSkip.BACKOFF
        else -> null
    }

    private fun ok(r: InstallResult): Boolean = r is InstallResult.Installed || r is InstallResult.UpToDate

    private fun failed(r: InstallResult): AutoInstallOutcome {
        log("dict auto-install: $r")
        recordFailure(nowMs())
        return AutoInstallOutcome.Ran(r)
    }

    companion object {
        const val RETRY_AFTER_MS: Long = 3_600_000L
    }
}
