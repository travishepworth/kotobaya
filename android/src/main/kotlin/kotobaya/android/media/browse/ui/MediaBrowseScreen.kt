package kotobaya.android.media.browse.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotobaya.android.R
import kotobaya.android.grammar.ui.CaptureEntrySlot
import kotobaya.android.i18n.T
import kotobaya.android.media.browse.CoverageView
import kotobaya.android.media.browse.Loaded
import kotobaya.android.media.browse.MediaBrowseIntents
import kotobaya.android.media.browse.MediaBrowseUiState
import kotobaya.android.media.browse.MediaRow
import kotobaya.android.media.browse.SentenceView

/** The Media tab: the list, or the selected media's detail. [captureEntry] is each sentence's grammar capture. */
@Composable
fun MediaBrowseScreen(state: MediaBrowseUiState, intents: MediaBrowseIntents, captureEntry: CaptureEntrySlot) {
    val sel = state.selected
    if (sel != null) {
        MediaDetail(sel, state.coverage, state.sentences, intents, captureEntry)
        return
    }
    val list = state.rows.orEmpty()
    if (state.rows != null && list.isEmpty()) Text(T(R.string.browse_empty), Modifier.padding(16.dp))
    LazyColumn(Modifier.padding(16.dp)) {
        items(list) { r ->
            Column(Modifier.fillMaxWidth().clickable { intents.open(r) }.padding(vertical = 8.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                Text(T(R.string.browse_row_meta, r.kind, r.parseStatus))
            }
        }
    }
}

@Composable
private fun MediaDetail(
    row: MediaRow,
    coverage: Loaded<CoverageView>?,
    sentences: Loaded<List<SentenceView>>?,
    intents: MediaBrowseIntents,
    captureEntry: CaptureEntrySlot,
) {
    LazyColumn(Modifier.padding(16.dp)) {
        item {
            OutlinedButton(onClick = intents::back) { Text(T(R.string.browse_back)) }
            Text(row.title, style = MaterialTheme.typography.titleLarge)
            when (coverage) {
                null -> Text(T(R.string.browse_loading))
                is Loaded.NeedsConnection -> Text(T(R.string.browse_needs_connection))
                is Loaded.Failed -> Text(T(R.string.browse_error))
                is Loaded.Data -> CoverageBlock(coverage)
            }
        }
        if (sentences is Loaded.Data) {
            item { Text(T(R.string.browse_sentences_title), style = MaterialTheme.typography.titleMedium) }
            items(sentences.value, key = { it.id }) { sent ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(sent.text)
                    if (sent.isGenerated) Text(T(R.string.browse_generated))
                    captureEntry(sent.id, row.id, sent.text)
                    val hash = sent.audioBlobHash
                    if (hash != null) {
                        OutlinedButton(onClick = { intents.play(hash) }) { Text(T(R.string.browse_play)) }
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
