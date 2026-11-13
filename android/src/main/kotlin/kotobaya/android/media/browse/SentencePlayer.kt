package kotobaya.android.media.browse

import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.media.BlobDownloader
import kotobaya.android.media.BlobReason
import kotobaya.android.media.BlobState
import kotobaya.android.media.BlobStore
import kotobaya.android.media.DownloadResult

/** Plays a sentence clip from the [BlobStore]; downloads it on demand when not cached. */
class SentencePlayer(private val blobs: BlobStore, private val downloader: BlobDownloader) {
    private var player: MediaPlayer? = null

    suspend fun play(hash: String) {
        val file = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = blobs.entries().any { it.hash == hash && it.state == BlobState.CACHED } && blobs.file(hash).isFile
            if (!cached) {
                blobs.want(hash, BlobReason.MEDIA, now)
                val r = downloader.download(hash, Long.MAX_VALUE)
                if (r is DownloadResult.Stored) blobs.markCached(hash, r.size, r.contentType, now) else return@withContext null
            }
            blobs.touch(hash, now)
            blobs.file(hash)
        } ?: return
        release()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { release() }
            prepare()
            start()
        }
    }

    fun release() {
        player?.release()
        player = null
    }
}
