package kotobaya.android.sync.content

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.media.BlobDownloader
import kotobaya.android.media.BlobStore
import kotobaya.android.media.DownloadResult
import org.koin.core.context.GlobalContext

/** [SnapshotFetcher] over the card-audio downloader (Range resume, SHA-256 verify) into the blob directory. */
class BlobSnapshotFetcher(private val downloader: BlobDownloader, private val store: BlobStore) : SnapshotFetcher {
    override fun fetch(hash: String): FetchResult = when (val r = downloader.download(hash, MAX_COMPRESSED_BYTES)) {
        is DownloadResult.Stored -> FetchResult.Ok(store.file(hash))
        DownloadResult.HashMismatch -> FetchResult.Permanent("the server's bytes do not match the hash")
        DownloadResult.OverBudget -> FetchResult.Permanent("larger than $MAX_COMPRESSED_BYTES bytes")
        // 404: the announcement may be ahead of the object store; transient like a network failure
        DownloadResult.NotFound -> FetchResult.Transient("404")
        is DownloadResult.Failed -> FetchResult.Transient(r.reason)
    }

    private companion object {
        /** SNAPSHOT.md 2.7: compressed archives are at most 512 MiB. */
        const val MAX_COMPRESSED_BYTES: Long = 512L * 1024 * 1024
    }
}

/**
 * WorkManager scheduling of [ContentInstaller] (AND-DECKCONTENT): unique one-shot, any connected network (deck
 * items are small and the new-card queue needs them), exponential backoff while a download fails transiently.
 * Enqueued after every completed sync and at startup when something is pending.
 */
object ContentInstallWork {
    const val NAME: String = "kotobaya-content-install"

    fun enqueue(wm: WorkManager) {
        val req = OneTimeWorkRequestBuilder<ContentInstallWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: a queued or running worker re-reads the pending list until it is empty
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
    }

    /** Enqueues only when [installer] has something pending (cheap query; call off the main thread). */
    fun enqueueIfPending(installer: ContentInstaller, wm: () -> WorkManager?) {
        val n = runCatching { installer.pending().size }
            .onFailure { Log.e(ContentInstaller.TAG, "pending content check failed", it) }
            .getOrDefault(0)
        if (n > 0) runCatching { wm()?.let(::enqueue) }
    }
}

class ContentInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val installer = GlobalContext.getOrNull()?.getOrNull<ContentInstaller>() ?: return@withContext Result.retry()
        val s = installer.runAll()
        Log.i(ContentInstaller.TAG, "content install run: $s")
        if (s.retry > 0) Result.retry() else Result.success()
    }
}
