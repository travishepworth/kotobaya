package kotobaya.android.media.browse

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.media.BlobDownloader
import kotobaya.android.media.BlobReason
import kotobaya.android.media.BlobState
import kotobaya.android.media.BlobStore
import kotobaya.android.media.DownloadResult
import org.koin.compose.koinInject

@Composable
fun MediaBrowseScreen() {
    val source = koinInject<MediaListSource>()
    var rows by remember { mutableStateOf<List<MediaRow>?>(null) }
    var selected by remember { mutableStateOf<MediaRow?>(null) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { source.list() } }
    val sel = selected
    if (sel != null) {
        MediaDetail(sel) { selected = null }
        return
    }
    val list = rows.orEmpty()
    if (rows != null && list.isEmpty()) Text(T(R.string.browse_empty), Modifier.padding(16.dp))
    LazyColumn(Modifier.padding(16.dp)) {
        items(list) { r ->
            Column(Modifier.fillMaxWidth().clickable { selected = r }.padding(vertical = 8.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                Text(T(R.string.browse_row_meta, r.kind, r.parseStatus))
            }
        }
    }
}

@Composable
private fun MediaDetail(row: MediaRow, onBack: () -> Unit) {
    val repo = koinInject<MediaBrowseRepository>()
    var coverage by remember { mutableStateOf<Loaded<CoverageView>?>(null) }
    var sentences by remember { mutableStateOf<Loaded<List<SentenceView>>?>(null) }
    LaunchedEffect(row.id) {
        coverage = repo.coverage(row.id)
        sentences = repo.sentences(row.id)
    }
    val blobs = koinInject<BlobStore>()
    val downloader = koinInject<BlobDownloader>()
    val player = remember { SentencePlayer(blobs, downloader) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            OutlinedButton(onClick = onBack) { Text(T(R.string.browse_back)) }
            Text(row.title, style = MaterialTheme.typography.titleLarge)
            when (val c = coverage) {
                null -> Text(T(R.string.browse_loading))
                is Loaded.NeedsConnection -> Text(T(R.string.browse_needs_connection))
                is Loaded.Failed -> Text(T(R.string.browse_error))
                is Loaded.Data -> CoverageBlock(c)
            }
        }
        val s = sentences
        if (s is Loaded.Data) {
            item { Text(T(R.string.browse_sentences_title), style = MaterialTheme.typography.titleMedium) }
            items(s.value, key = { it.id }) { sent ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(sent.text)
                    if (sent.isGenerated) Text(T(R.string.browse_generated))
                    kotobaya.android.grammar.GrammarCaptureEntry(sent.id, row.id, sent.text)
                    val hash = sent.audioBlobHash
                    if (hash != null) {
                        OutlinedButton(onClick = { scope.launch { player.play(hash) } }) { Text(T(R.string.browse_play)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverageBlock(c: Loaded.Data<CoverageView>) {
    val v = c.value
    val t = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updatedAtMs))
    Text(T(if (c.fromCache) R.string.browse_cached_stamp else R.string.browse_updated_stamp, t))
    Text(T(R.string.browse_token_pct, v.tokenKnownPct))
    Text(T(R.string.browse_unique_counts, v.unique.known, v.unique.learning, v.unique.unknown))
    Text(T(R.string.browse_token_counts, v.tokens.known, v.tokens.learning, v.tokens.unknown))
    if (v.curve.size >= 2) {
        val color = MaterialTheme.colorScheme.primary
        Text(T(R.string.browse_curve_title))
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val maxW = v.curve.maxOf { it.words }.coerceAtLeast(1)
            val lo = v.curve.minOf { it.knownPct }
            val hi = v.curve.maxOf { it.knownPct }.let { if (it - lo < 0.01) lo + 1 else it }
            val pts = v.curve.map {
                Offset(
                    it.words.toFloat() / maxW * size.width,
                    size.height - ((it.knownPct - lo) / (hi - lo)).toFloat() * size.height,
                )
            }
            pts.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 4f) }
        }
    }
    if (v.segments.isNotEmpty()) Text(T(R.string.browse_segments_title), style = MaterialTheme.typography.titleMedium)
    v.segments.forEach { s -> Text(T(R.string.browse_segment_row, s.title, s.knownPct, s.uniqueUnknown)) }
}

/** Plays a sentence clip from the [BlobStore]; downloads it on demand when not cached. */
internal class SentencePlayer(private val blobs: BlobStore, private val downloader: BlobDownloader) {
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
