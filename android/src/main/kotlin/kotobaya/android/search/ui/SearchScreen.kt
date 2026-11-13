package kotobaya.android.search.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.search.SearchDetail
import kotobaya.android.search.SearchIntents
import kotobaya.android.search.SearchMode
import kotobaya.android.search.SearchResults
import kotobaya.android.search.SearchUiState
import kotobaya.android.word.ui.NoteDetailScreen
import kotobaya.android.word.ui.WordDetailScreen
import kotobaya.contracts.search.SearchErrorCode

/** The Search tab: words or my cards; a result opens its word or note detail in place. */
@Composable
fun SearchScreen(state: SearchUiState, intents: SearchIntents) {
    val open = state.detail
    if (open != null) {
        BackHandler(onBack = intents::closeDetail)
        when (open) {
            is SearchDetail.Word -> WordDetailScreen(open.load, onBack = intents::closeDetail)
            is SearchDetail.Note -> NoteDetailScreen(open.load, onBack = intents::closeDetail)
        }
        return
    }
    val mode = state.mode

    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((m, label) in listOf(SearchMode.WORDS to R.string.search_mode_words, SearchMode.CARDS to R.string.search_mode_cards)) {
                    if (mode == m) {
                        Button(onClick = {}) { Text(T(label)) }
                    } else {
                        OutlinedButton(onClick = { intents.setMode(m) }) { Text(T(label)) }
                    }
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = intents::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(T(if (mode == SearchMode.WORDS) R.string.search_hint_words else R.string.search_hint_cards)) },
            )
            Button(onClick = intents::search) { Text(T(R.string.search_go)) }
        }
        when (val s = state.results) {
            SearchResults.Idle -> {}
            SearchResults.Busy -> item { Text(T(R.string.word_loading)) }
            is SearchResults.Failed -> item { Text(T(if (s.offline) R.string.search_needs_connection else R.string.search_failed)) }
            is SearchResults.Invalid -> items(s.errors) { e ->
                Text(
                    T(errorRes(e.code)) + " " + T(R.string.search_error_position, e.span.start),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is SearchResults.Words -> {
                if (s.items.isEmpty()) item { Text(T(R.string.search_no_results)) }
                items(s.items, key = { it.seq }) { w ->
                    Column(Modifier.fillMaxWidth().clickable { intents.openWord(w.seq) }.padding(vertical = 4.dp)) {
                        Text(w.headword, style = MaterialTheme.typography.titleMedium)
                        Text("${w.reading} ${w.gloss}".trim())
                        Text(T(statusRes(w.status)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.next != null) item { OutlinedButton(onClick = intents::moreWords) { Text(T(R.string.search_more)) } }
            }
            is SearchResults.Cards -> {
                if (s.fromServer) item { Text(T(R.string.search_from_server), style = MaterialTheme.typography.bodySmall) }
                if (s.hits.isEmpty()) item { Text(T(R.string.search_no_results)) }
                items(s.hits.take(s.shown), key = { it.cardId }) { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            val seq = c.entrySeq
                            if (seq != null) intents.openWord(seq) else intents.openNote(c.noteId)
                        }.padding(vertical = 4.dp),
                    ) {
                        Text(c.title.ifEmpty { c.kind }, style = MaterialTheme.typography.titleMedium)
                        val suspended = if (c.suspended) " · " + T(R.string.search_card_suspended) else ""
                        Text(T(R.string.search_card_line, c.kind, T(cardStateRes(c.state))) + suspended, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.hits.size > s.shown) {
                    item { OutlinedButton(onClick = intents::moreCards) { Text(T(R.string.search_more)) } }
                }
            }
        }
    }
}

private fun errorRes(code: SearchErrorCode): Int = when (code) {
    SearchErrorCode.UNKNOWN_KEY -> R.string.search_error_unknown_key
    SearchErrorCode.BAD_VALUE -> R.string.search_error_bad_value
    SearchErrorCode.MISSING_VALUE -> R.string.search_error_missing_value
    SearchErrorCode.UNBALANCED_PAREN -> R.string.search_error_unbalanced_paren
    SearchErrorCode.BAD_QUOTE -> R.string.search_error_bad_quote
    SearchErrorCode.DANGLING_OPERATOR -> R.string.search_error_dangling_operator
}

fun statusRes(wire: String): Int = when (wire) {
    "learning" -> R.string.status_learning
    "known" -> R.string.status_known
    "never_forget" -> R.string.status_never_forget
    "blacklisted" -> R.string.status_blacklisted
    "ignored" -> R.string.status_ignored
    else -> R.string.status_new
}

fun cardStateRes(wire: String): Int = when (wire) {
    "learning" -> R.string.card_state_learning
    "review" -> R.string.card_state_review
    "relearning" -> R.string.card_state_relearning
    else -> R.string.card_state_new
}
