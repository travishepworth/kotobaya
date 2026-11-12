package kotobaya.android.dict

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One entry of `DictRelease.artifacts` (DICT_PACKAGE 1). */
data class Artifact(
    val kind: String,
    val scope: String,
    val fromRelease: Long?,
    val formatVersion: Int,
    val blobHash: String,
    val sizeBytes: Long,
    val uncompressedSizeBytes: Long,
)

/** `GET /dict/release`, reduced to what the installer needs. */
data class DictReleaseInfo(val id: Long, val artifacts: List<Artifact>) {
    companion object {
        fun parse(json: JSONObject): DictReleaseInfo {
            val arr = json.optJSONArray("artifacts") ?: org.json.JSONArray()
            val list = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Artifact(
                    kind = a.getString("kind"),
                    scope = a.getString("scope"),
                    fromRelease = if (a.isNull("from_release")) null else a.getLong("from_release"),
                    formatVersion = a.getInt("format_version"),
                    blobHash = a.getString("blob_hash"),
                    sizeBytes = a.getLong("size_bytes"),
                    uncompressedSizeBytes = a.getLong("uncompressed_size_bytes"),
                )
            }
            return DictReleaseInfo(json.getLong("id"), list)
        }
    }
}

/** `GET /blobs/{hash}` answered 404. */
class BlobNotFound(hash: String) : Exception("blob $hash not found")

/** Server access of the installer. */
interface DictRemote {
    /** The current release, or null on `404` (none loaded yet). */
    suspend fun release(): DictReleaseInfo?

    /**
     * Downloads blob [hash] into [part], appending when [part] already has bytes (`Range` resume).
     * Throws [BlobNotFound] on 404; any other failure is an exception that keeps the partial file.
     */
    suspend fun fetchBlob(hash: String, part: File, onProgress: (bytes: Long) -> Unit)
}

/** Where the dictionary lives: `<dir>/dictionary.db`, staging `<dir>/dictionary.db.staging`, downloads `<dir>/dl/`. */
class DictStore(val dir: File) {
    val installed: File = File(dir, "dictionary.db")
    val staging: File = File(dir, "dictionary.db.staging")
    val downloads: File = File(dir, "dl")

    private val generationFlow = MutableStateFlow(0L)

    /** Increments whenever the installed file is replaced; readers reopen on change. */
    val generation: StateFlow<Long> get() = generationFlow

    init {
        dir.mkdirs()
    }

    internal fun bump() {
        generationFlow.value = generationFlow.value + 1
    }

    /** Step 10: an install interrupted before the rename never took effect. */
    fun cleanStaging() {
        PackageFile.deleteWithSidecars(staging)
    }

    fun info(): PackageInfo? = PackageFile.readInfo(installed)

    fun installedBytes(): Long = if (installed.isFile) installed.length() else 0L
}

sealed class InstallResult {
    /** `404`: no release on the server yet. */
    object NoRelease : InstallResult()

    object UpToDate : InstallResult()

    /** The installed id is greater than the server's: the installed file is kept. */
    object DowngradeIgnored : InstallResult()

    data class Installed(val releaseId: Long, val viaDiff: Boolean) : InstallResult()

    /** No artifact the app can read: "dictionary update needs a newer app". */
    object NeedsNewerApp : InstallResult()

    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : InstallResult()

    data class Failed(val reason: String) : InstallResult()
}

/** Progress and outcome for the Settings screen. */
sealed class InstallState {
    object Idle : InstallState()

    data class Running(val stage: Stage, val doneBytes: Long = 0, val totalBytes: Long = 0) : InstallState()

    data class Finished(val result: InstallResult) : InstallState()

    enum class Stage { CHECKING, DOWNLOADING, VERIFYING, APPLYING }
}
