package kotobaya.android.dict

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotobaya.db.app.AppDatabase

/** A diff attempt failed in a way that section 5.9 answers with the full artifact. */
private class DiffFailed(message: String) : Exception(message)

/**
 * Device install and update of the dictionary package, DICT_PACKAGE section 5.
 *
 * [scope] is the user's chosen scope (read per run). [work] runs every digest, decompress and diff step
 * (seconds to minutes on the `all` scope) and must not be the main thread; [onWork] is called with the step
 * name at the start of each such step (tests use it to assert the dispatcher). Runs are serialised.
 */
class DictInstaller(
    private val store: DictStore,
    private val remote: DictRemote,
    private val scope: () -> String,
    private val db: AppDatabase,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val work: CoroutineDispatcher = Dispatchers.IO,
    private val onWork: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private val stateFlow = MutableStateFlow<InstallState>(InstallState.Idle)

    /** Progress and last outcome. */
    val state: StateFlow<InstallState> get() = stateFlow

    /** Step 10 and the `sync_state` cache rule of section 5. Idempotent; call before the first read. */
    suspend fun startup() = withContext(work) {
        store.cleanStaging()
        syncStateFromFile()
    }

    private fun syncStateFromFile() {
        val info = store.info()
        if (info == null) {
            db.metaLimitsQueries.deleteSyncState(DICT_RELEASE_KEY)
        } else if (db.syncOpsQueries.getSyncState(DICT_RELEASE_KEY).executeAsOneOrNull() != info.releaseId.toString()) {
            db.syncOpsQueries.putSyncState(DICT_RELEASE_KEY, info.releaseId.toString())
        }
    }

    /** One update attempt (decision, download, install). Never throws except for cancellation. */
    suspend fun run(): InstallResult = lock.withLock {
        stateFlow.value = InstallState.Running(InstallState.Stage.CHECKING)
        val result = try {
            attempt()
        } catch (e: CancellationException) {
            stateFlow.value = InstallState.Idle
            throw e
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        withContext(work) { store.cleanStaging() }
        stateFlow.value = InstallState.Finished(result)
        result
    }

    private suspend fun attempt(): InstallResult {
        var restarts = 0
        while (true) {
            try {
                return decideAndInstall()
            } catch (e: BlobNotFound) {
                // Section 4: a 404 means the release moved on; re-read it and decide again (once).
                if (restarts++ >= 1) return InstallResult.Failed("blob missing on the server")
                log("dict: blob ${e.message}; re-reading the release")
            }
        }
    }

    private suspend fun decideAndInstall(): InstallResult {
        val release = remote.release() ?: return InstallResult.NoRelease
        val chosen = scope()
        val installed = withContext(work) { store.info() }

        if (installed != null && installed.releaseId > release.id) return InstallResult.DowngradeIgnored
        if (installed != null && installed.releaseId == release.id && installed.scope == chosen) return InstallResult.UpToDate

        val candidates = release.artifacts.filter { it.scope == chosen && supported(it) }
        val diff = candidates.firstOrNull {
            it.kind == "diff" && installed != null && it.fromRelease == installed.releaseId &&
                installed.userVersion == it.formatVersion && installed.scope == chosen
        }
        val full = candidates.filter { it.kind == "full" }.maxByOrNull { it.formatVersion }
        if (diff == null && full == null) return InstallResult.NeedsNewerApp

        if (diff != null) {
            val need = diff.sizeBytes + diff.uncompressedSizeBytes + store.installedBytes()
            val have = freeSpace(store.dir)
            if (have >= need) {
                try {
                    installDiff(release, diff)
                    return InstallResult.Installed(release.id, viaDiff = true)
                } catch (e: DiffFailed) {
                    log("dict: diff failed (${e.message}); falling back to full")
                }
            } else if (full == null) {
                return InstallResult.InsufficientSpace(need, have)
            }
        }
        if (full == null) return InstallResult.NeedsNewerApp
        val need = full.sizeBytes + full.uncompressedSizeBytes
        val have = freeSpace(store.dir)
        if (have < need) return InstallResult.InsufficientSpace(need, have)
        installFull(release, full)
        return InstallResult.Installed(release.id, viaDiff = false)
    }

    private fun supported(a: Artifact) = when (a.kind) {
        "full" -> a.formatVersion == DictSchema.USER_VERSION
        "diff" -> a.formatVersion == DictSchema.DIFF_VERSION
        else -> false
    }

    // ------------------------------------------------------------------ download (5.6)

    /** Resumable download with length and SHA-256 check; one retry from scratch after a mismatch. */
    private suspend fun download(a: Artifact): File {
        store.downloads.mkdirs()
        val part = File(store.downloads, a.blobHash + ".part")
        for (attempt in 0..1) {
            if (part.exists() && part.length() > a.sizeBytes) part.delete()
            if (!part.exists() || part.length() < a.sizeBytes) {
                stateFlow.value = InstallState.Running(InstallState.Stage.DOWNLOADING, part.length(), a.sizeBytes)
                remote.fetchBlob(a.blobHash, part) { bytes ->
                    stateFlow.value = InstallState.Running(InstallState.Stage.DOWNLOADING, bytes, a.sizeBytes)
                }
            }
            stateFlow.value = InstallState.Running(InstallState.Stage.VERIFYING, part.length(), a.sizeBytes)
            val ok = withContext(work) {
                onWork("verify")
                part.length() == a.sizeBytes && PackageFile.sha256Hex(part) == a.blobHash
            }
            if (ok) return part
            part.delete()
            log("dict: download of ${a.blobHash} failed verification (attempt ${attempt + 1})")
        }
        throw IOException("download of ${a.blobHash} failed length/hash verification twice")
    }

    // ------------------------------------------------------------------ install (5.7-5.8)

    private suspend fun installFull(release: DictReleaseInfo, a: Artifact) {
        val blob = download(a)
        stateFlow.value = InstallState.Running(InstallState.Stage.APPLYING)
        withContext(work) {
            onWork("full")
            store.cleanStaging()
            PackageFile.decompress(blob, store.staging)
            blob.delete()
            PackageFile.verifyFull(store.staging, release.id, a.scope, a.formatVersion)
            swap()
        }
    }

    private suspend fun installDiff(release: DictReleaseInfo, a: Artifact) {
        val blob = try {
            download(a)
        } catch (e: BlobNotFound) {
            throw DiffFailed("404")
        } catch (e: IOException) {
            // Hash/length failure after the retry; a plain network failure is not a diff failure.
            if (e.message?.contains("verification") == true) throw DiffFailed(e.message ?: "verification") else throw e
        }
        stateFlow.value = InstallState.Running(InstallState.Stage.APPLYING)
        withContext(work) {
            onWork("diff")
            store.cleanStaging()
            try {
                store.installed.copyTo(store.staging, overwrite = true)
                val bytes = PackageFile.decompressToBytes(blob)
                PackageFile.applyDiff(store.staging, bytes, a.scope, a.fromRelease ?: -1, release.id)
            } catch (e: PackageException) {
                store.cleanStaging()
                throw DiffFailed(e.message ?: "package")
            } catch (e: com.github.luben.zstd.ZstdException) {
                store.cleanStaging()
                throw DiffFailed("zstd: ${e.message}")
            } catch (e: org.json.JSONException) {
                store.cleanStaging()
                throw DiffFailed("json: ${e.message}")
            } catch (e: android.database.SQLException) {
                store.cleanStaging()
                throw DiffFailed("sqlite: ${e.message}")
            }
            blob.delete()
            swap()
        }
    }

    /** Rename the staged file over the installed one, then refresh the `sync_state` cache from the new file. */
    private fun swap() {
        PackageFile.atomicReplace(store.staging, store.installed)
        store.downloads.deleteRecursively()
        store.bump()
        syncStateFromFile()
    }

    companion object {
        const val DICT_RELEASE_KEY = "dict_release_id"
    }
}
