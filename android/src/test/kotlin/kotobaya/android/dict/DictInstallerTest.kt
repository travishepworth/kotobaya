package kotobaya.android.dict

import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotobaya.db.app.AppDatabase
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DictInstallerTest {
    private lateinit var dir: File
    private lateinit var store: DictStore
    private lateinit var db: AppDatabase
    private lateinit var remote: FakeRemote
    private var scope = "top60k"
    private var free = Long.MAX_VALUE
    private val threads = mutableListOf<String>()
    private val workThread = Executors.newSingleThreadExecutor { Thread(it, "dict-work") }
    private lateinit var installer: DictInstaller

    private val eat = Fx(1, "食べる", "たべる", listOf("to eat"), 100)
    private val cat = Fx(2, "猫", "ねこ", listOf("cat"), 200)
    private val school = Fx(3, "学校", "がっこう", listOf("school"), 150)
    private val newWord = Fx(6, "雨", "あめ", listOf("rain"), 400)

    private lateinit var a: File // release 1
    private lateinit var b: File // release 2: eat re-ranked, school gone, rain added

    @Before
    fun setUp() {
        dir = File.createTempFile("dict", "").also { it.delete(); it.mkdirs() }
        store = DictStore(File(dir, "store"))
        db = AppDatabase(AndroidSqliteDriver(AppDatabase.Schema, ApplicationProvider.getApplicationContext(), null))
        remote = FakeRemote()
        a = PackageFixtures.create(File(dir, "a.db"), 1, "top60k", listOf(eat, cat, school))
        b = PackageFixtures.create(
            File(dir, "b.db"), 2, "top60k",
            listOf(Fx(1, "食べる", "たべる", listOf("to eat"), 90), cat, newWord),
        )
        installer = DictInstaller(
            store, remote, { scope }, db, freeSpace = { free },
            work = workThread.asCoroutineDispatcher(), onWork = { threads += Thread.currentThread().name },
        )
    }

    private fun publishFull(release: Long, file: File, scope: String = "top60k"): Artifact {
        val blob = PackageFixtures.zstd(file)
        remote.blobs[PackageFixtures.sha256(blob)] = blob
        return PackageFixtures.artifact("full", scope, null, blob, file.length())
    }

    private fun publishDiff(from: File, to: File, fromId: Long, tamper: (JSONObject) -> Unit = {}): Artifact {
        val json = PackageFixtures.diff(from, to, tamper).toString().toByteArray()
        val blob = PackageFixtures.zstd(json)
        remote.blobs[PackageFixtures.sha256(blob)] = blob
        return PackageFixtures.artifact("diff", "top60k", fromId, blob, json.size.toLong())
    }

    private fun digests(file: File): Map<String, String> {
        val d = PackageFile.openReadOnly(file)
        try {
            return DictSchema.tables.associate { it.name to PackageFile.tableDigest(d, it) }
        } finally {
            d.close()
        }
    }

    private fun installA() {
        remote.release = DictReleaseInfo(1, listOf(publishFull(1, a)))
        assertEquals(InstallResult.Installed(1, false), runBlocking { installer.run() })
        remote.fetched.clear()
        remote.offsets.clear()
    }

    private fun cacheKey() = db.syncOpsQueries.getSyncState(DictInstaller.DICT_RELEASE_KEY).executeAsOneOrNull()

    @Test
    fun fullInstall() {
        remote.release = DictReleaseInfo(1, listOf(publishFull(1, a)))
        assertEquals(InstallResult.Installed(1, false), runBlocking { installer.run() })
        assertEquals(digests(a), digests(store.installed))
        assertEquals(PackageInfo(1, "top60k", "v1", 1), store.info())
        assertEquals("1", cacheKey())
        assertFalse(store.staging.exists())
        assertFalse(store.downloads.exists())
        // Read-only open: writes are refused.
        val ro = PackageFile.openReadOnly(store.installed)
        assertTrue(runCatching { ro.execSQL("DELETE FROM kanji") }.isFailure)
        ro.close()
    }

    @Test
    fun diffInstallUsesOnlyTheDiff() {
        installA()
        val diff = publishDiff(a, b, 1)
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b), diff))
        assertEquals(InstallResult.Installed(2, true), runBlocking { installer.run() })
        assertEquals(listOf(diff.blobHash), remote.fetched)
        assertEquals(digests(b), digests(store.installed))
        assertEquals("2", cacheKey())
    }

    @Test
    fun corruptDownloadIsRetriedOnceThenFailsAndLeavesTheOldFile() {
        installA()
        val before = store.installed.readBytes()
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b)))
        remote.corruptNext = 2
        val r = runBlocking { installer.run() }
        assertTrue(r is InstallResult.Failed, r.toString())
        assertEquals(2, remote.fetched.size)
        assertTrue(before.contentEquals(store.installed.readBytes()))
        assertEquals("1", cacheKey())
        assertFalse(store.staging.exists())

        // One corrupt attempt is absorbed by the retry.
        remote.fetched.clear()
        remote.corruptNext = 1
        assertEquals(InstallResult.Installed(2, false), runBlocking { installer.run() })
        assertEquals(2, remote.fetched.size)
    }

    @Test
    fun failedDiffFallsBackToFull() {
        installA()
        // Same row counts, wrong digest: only the content digest catches it (3.4 step 6).
        val bad = publishDiff(a, b, 1) { it.getJSONObject("table_digests").put("dict_entry", "0".repeat(64)) }
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b), bad))
        assertEquals(InstallResult.Installed(2, false), runBlocking { installer.run() })
        assertEquals(2, remote.fetched.size)
        assertEquals(digests(b), digests(store.installed))
        assertFalse(store.staging.exists())

        // 404 on the diff blob falls back too.
        runBlocking { store.installed.delete(); installA() }
        val gone = publishDiff(a, b, 1)
        remote.missing += gone.blobHash
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b), gone))
        assertEquals(InstallResult.Installed(2, false), runBlocking { installer.run() })
    }

    @Test
    fun wrongBaseDiffIsNotUsed() {
        installA()
        val diff = publishDiff(a, b, 7) // claims to upgrade from release 7
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b), diff))
        assertEquals(InstallResult.Installed(2, false), runBlocking { installer.run() })
        assertFalse(diff.blobHash in remote.fetched)

        // Envelope disagreeing with the artifact (right artifact base, diff built for release 7): rejected, full used.
        runBlocking { store.installed.delete(); installA() }
        val lying = publishDiff(a, b, 1) { it.put("from_release", 7) }
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b), lying))
        assertEquals(InstallResult.Installed(2, false), runBlocking { installer.run() })
        assertTrue(lying.blobHash in remote.fetched)
    }

    @Test
    fun interruptedInstallKeepsInstalledFileAndStagingIsCleanedOnRestart() {
        installA()
        val before = store.installed.readBytes()
        val full = publishFull(2, b)
        remote.release = DictReleaseInfo(2, listOf(full))
        remote.interruptAfter = 100
        assertTrue(runBlocking { installer.run() } is InstallResult.Failed)
        assertTrue(before.contentEquals(store.installed.readBytes()))
        assertEquals(1L, store.info()?.releaseId)

        // A crash mid-install left staging files behind.
        store.staging.writeText("junk")
        File(store.staging.path + "-journal").writeText("junk")
        db.syncOpsQueries.putSyncState(DictInstaller.DICT_RELEASE_KEY, "99")
        runBlocking { installer.startup() }
        assertFalse(store.staging.exists())
        assertFalse(File(store.staging.path + "-journal").exists())
        assertEquals("1", cacheKey()) // rewritten from the file's own row

        // The retry resumes the partial download with a Range from the byte already on disk.
        remote.offsets.clear()
        assertEquals(InstallResult.Installed(2, false), runBlocking { installer.run() })
        assertEquals(listOf(100L), remote.offsets)
    }

    @Test
    fun startupDeletesTheCacheKeyWithoutAFile() {
        db.syncOpsQueries.putSyncState(DictInstaller.DICT_RELEASE_KEY, "5")
        runBlocking { installer.startup() }
        assertNull(cacheKey())
    }

    @Test
    fun downgradeIsIgnored() {
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b)))
        runBlocking { installer.run() }
        val before = store.installed.readBytes()
        remote.release = DictReleaseInfo(1, listOf(publishFull(1, a)))
        remote.fetched.clear()
        assertEquals(InstallResult.DowngradeIgnored, runBlocking { installer.run() })
        assertTrue(remote.fetched.isEmpty())
        assertTrue(before.contentEquals(store.installed.readBytes()))
    }

    @Test
    fun insufficientSpaceIsReportedBeforeDownloading() {
        val full = publishFull(1, a)
        remote.release = DictReleaseInfo(1, listOf(full))
        free = full.sizeBytes + full.uncompressedSizeBytes - 1
        val r = runBlocking { installer.run() }
        assertEquals(InstallResult.InsufficientSpace(full.sizeBytes + full.uncompressedSizeBytes, free), r)
        assertTrue(remote.fetched.isEmpty())
        assertFalse(store.installed.exists())
    }

    @Test
    fun heavyStepsRunOffTheCallingThread() {
        installA()
        remote.release = DictReleaseInfo(2, listOf(publishFull(2, b), publishDiff(a, b, 1)))
        threads.clear()
        val r = runBlocking { installer.run() }
        assertTrue(threads.isNotEmpty(), "$r")
        assertTrue(threads.all { it.startsWith("dict-work") }, threads.toString())
        assertNotEquals(Thread.currentThread().name, "dict-work")
        assertEquals(2, threads.size, "verify and diff steps: $r")
    }

    @Test
    fun noUsableArtifactNeedsNewerApp() {
        remote.release = DictReleaseInfo(1, listOf(PackageFixtures.artifact("full", "top60k", null, ByteArray(3), 3, format = 2)))
        assertEquals(InstallResult.NeedsNewerApp, runBlocking { installer.run() })
        remote.release = DictReleaseInfo(1, emptyList())
        assertEquals(InstallResult.NeedsNewerApp, runBlocking { installer.run() })
        remote.release = null
        assertEquals(InstallResult.NoRelease, runBlocking { installer.run() })
    }

    @Test
    fun changingScopeInstallsTheOtherFull() {
        installA()
        val all = PackageFixtures.create(File(dir, "all.db"), 1, "all", listOf(eat, cat, school, newWord))
        remote.release = DictReleaseInfo(1, listOf(publishFull(1, a), publishFull(1, all, "all")))
        scope = "all"
        assertEquals(InstallResult.Installed(1, false), runBlocking { installer.run() })
        assertEquals("all", store.info()?.scope)
        assertEquals(InstallResult.UpToDate, runBlocking { installer.run() })
    }

    @Test
    fun fullWithWrongReleaseInsideIsRejected() {
        val wrong = publishFull(5, a) // file says release 1
        remote.release = DictReleaseInfo(5, listOf(wrong))
        assertTrue(runBlocking { installer.run() } is InstallResult.Failed)
        assertFalse(store.installed.exists())
    }
}
