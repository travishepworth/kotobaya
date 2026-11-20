package kotobaya.android.dict

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** L-7 "Auto-install" (ADR-053): when it starts, and the two ways it is skipped that cost the user (metered, budget). */
class DictAutoInstallTest {
    private class Env(
        var paired: Boolean = true,
        var firstSync: Boolean = true,
        var unmetered: Boolean = true,
        var scope: String? = null,
        var chose: Boolean = false,
        var budget: Long = 1_000,
        var used: Long = 100,
        var dictBytes: Long = 0,
    ) : AutoInstallEnv {
        override fun paired() = paired

        override fun firstSyncDone() = firstSync

        override fun unmetered() = unmetered

        override fun installedScope() = scope

        override fun userChoseTop60k() = chose

        override fun budgetBytes() = budget

        override fun usedBytes() = used

        override fun installedDictionaryBytes() = dictBytes
    }

    private val release = DictReleaseInfo(
        7, listOf(Artifact("full", "all", null, DictSchema.USER_VERSION, "h", 300, 600)),
    )
    private val remote = object : DictRemote {
        var asked = 0

        override suspend fun release(): DictReleaseInfo? = release.also { asked++ }

        override suspend fun fetchBlob(hash: String, part: java.io.File, onProgress: (bytes: Long) -> Unit) = error("not used")
    }
    private val scopes = ArrayList<String>()
    private var runs = 0
    private var failedAt: Long? = null

    private fun auto(env: Env, result: InstallResult = InstallResult.Installed(7, false)) = DictAutoInstall(
        env, remote, installRunning = { false }, runInstall = { runs++; result }, setScope = { scopes += it },
        nowMs = { 10_000_000L }, failedAtMs = { failedAt }, recordFailure = { failedAt = it },
    )

    @Test
    fun installsTop60kThenAllWhenNothingIsInstalledOnAnUnmeteredNetwork() = runBlocking<Unit> {
        val o = auto(Env()).evaluate()
        assertIs<AutoInstallOutcome.Ran>(o)
        assertEquals(listOf("top60k", "all"), scopes)
        assertEquals(2, runs)
    }

    @Test
    fun installsAllOverTop60kInOneRun() = runBlocking<Unit> {
        auto(Env(scope = "top60k", dictBytes = 50, used = 150)).evaluate()
        assertEquals(listOf("all"), scopes)
        assertEquals(1, runs)
    }

    @Test
    fun isSkippedOnAMeteredNetworkWithoutAskingTheServer() = runBlocking<Unit> {
        val o = auto(Env(unmetered = false)).evaluate()
        assertEquals(AutoInstallOutcome.Skipped(AutoInstallSkip.METERED), o)
        assertEquals(0, runs)
        assertEquals(0, remote.asked)
    }

    @Test
    fun isSkippedWhenTheBudgetWouldBeExceeded() = runBlocking<Unit> {
        // used 100 (no dictionary yet) + 600 uncompressed = 700 fits 700, not 699
        assertIs<AutoInstallOutcome.Ran>(auto(Env(budget = 700)).evaluate())
        runs = 0
        scopes.clear()
        val o = auto(Env(budget = 699)).evaluate()
        assertEquals(AutoInstallOutcome.Skipped(AutoInstallSkip.OVER_BUDGET), o)
        assertEquals(0, runs)
        assertEquals(emptyList(), scopes, "the scope setting is left alone")
        // the installed dictionary is replaced, not added to
        assertIs<AutoInstallOutcome.Ran>(auto(Env(scope = "top60k", budget = 700, used = 300, dictBytes = 200)).evaluate())
    }

    @Test
    fun otherConditionsSkip() = runBlocking<Unit> {
        suspend fun reason(env: Env) = (auto(env).evaluate() as AutoInstallOutcome.Skipped).reason
        assertEquals(AutoInstallSkip.NOT_PAIRED, reason(Env(paired = false)))
        assertEquals(AutoInstallSkip.FIRST_SYNC_PENDING, reason(Env(firstSync = false)))
        assertEquals(AutoInstallSkip.ALREADY_ALL, reason(Env(scope = "all")))
        assertEquals(AutoInstallSkip.USER_CHOSE_TOP60K, reason(Env(scope = "top60k", chose = true)))
        failedAt = 10_000_000L - 1_000
        assertEquals(AutoInstallSkip.BACKOFF, reason(Env()))
        assertEquals(0, runs)
    }

    @Test
    fun aFailedRunBacksOffForAnHour() = runBlocking<Unit> {
        val a = auto(Env(), InstallResult.Failed("net"))
        assertIs<AutoInstallOutcome.Ran>(a.evaluate())
        assertEquals(10_000_000L, failedAt)
        assertEquals(AutoInstallOutcome.Skipped(AutoInstallSkip.BACKOFF), a.evaluate())
    }
}
