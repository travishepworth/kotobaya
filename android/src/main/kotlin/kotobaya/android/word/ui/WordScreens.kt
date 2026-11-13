package kotobaya.android.word.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.search.ui.cardStateRes
import kotobaya.android.search.ui.statusRes
import kotobaya.android.word.NoteLoad
import kotobaya.android.word.NoteView
import kotobaya.android.word.WordDetail
import kotobaya.android.word.WordLoad

/** Word detail: entry data plus the user's notes, cards and status for it. [load] null while loading. */
@Composable
fun WordDetailScreen(load: WordLoad?, onBack: () -> Unit) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        OutlinedButton(onClick = onBack) { Text(T(R.string.word_back)) }
        when (load) {
            null -> Text(T(R.string.word_loading))
            WordLoad.NotFound -> Text(T(R.string.word_not_found))
            is WordLoad.Failed -> Text(T(if (load.offline) R.string.word_needs_connection else R.string.word_failed))
            is WordLoad.Found -> WordBody(load.detail)
        }
    }
}

@Composable
private fun WordBody(d: WordDetail) {
    Text(d.headword, style = MaterialTheme.typography.headlineMedium)
    Text(d.reading, style = MaterialTheme.typography.titleMedium)
    if (d.retired) Text(T(R.string.word_retired))
    Text(T(R.string.word_status, T(statusRes(d.status))))
    if (d.kanjiForms.isNotEmpty()) Text(T(R.string.word_kanji_forms, d.kanjiForms.joinToString("、")))
    if (d.readings.isNotEmpty()) Text(T(R.string.word_readings, d.readings.joinToString("、")))
    if (d.kanji.isNotEmpty()) Text(T(R.string.word_kanji, d.kanji.joinToString(" ")))
    d.senses.forEachIndexed { i, s ->
        Text("${i + 1}. ${s.glosses.joinToString("; ")}")
        if (s.pos.isNotEmpty()) Text(s.pos.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    }
    Text(T(R.string.word_my_notes), style = MaterialTheme.typography.titleMedium)
    if (d.notes.isEmpty()) Text(T(R.string.word_no_notes))
    d.notes.forEach { NoteBlock(it) }
}

@Composable
private fun NoteBlock(n: NoteView) {
    Text(n.title)
    n.cards.forEach { c ->
        Text(T(R.string.word_card_line, c.kind, T(cardStateRes(c.state))) + if (c.suspended) " · " + T(R.string.search_card_suspended) else "")
    }
}

/** Detail of a note that has no dictionary entry (reached from a card result). [load] null while loading. */
@Composable
fun NoteDetailScreen(load: NoteLoad?, onBack: () -> Unit) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        OutlinedButton(onClick = onBack) { Text(T(R.string.word_back)) }
        Text(T(R.string.note_title), style = MaterialTheme.typography.titleLarge)
        when (load) {
            null -> Text(T(R.string.word_loading))
            NoteLoad.Unavailable -> Text(T(R.string.note_unavailable))
            is NoteLoad.Found -> {
                NoteBlock(load.note)
                if (load.mnemonic.isNotEmpty()) Text(T(R.string.note_mnemonic, load.mnemonic))
                if (load.tags.isNotEmpty()) Text(T(R.string.note_tags, load.tags.joinToString(", ")))
            }
        }
    }
}
