package kotobaya.android.search

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.word.NoteDetailScreen
import kotobaya.android.word.WordDetailScreen
import kotobaya.contracts.search.SearchError
import kotobaya.contracts.search.SearchErrorCode
import org.koin.compose.koinInject

private sealed interface Detail {
    data class Word(val seq: Long) : Detail
    data class Note(val noteId: String) : Detail
}

private enum class Mode { WORDS, CARDS }

private sealed interface ResultState {
    data object Idle : ResultState
    data object Busy : ResultState
    data class Words(val items: List<WordHit>, val next: String?) : ResultState
    data class Cards(val hits: List<CardHit>, val shown: Int, val fromServer: Boolean) : ResultState
    data class Invalid(val errors: List<SearchError>) : ResultState
    data class Failed(val offline: Boolean) : ResultState
}

@Composable
fun SearchScreen() {
    val words = koinInject<WordSearch>()
    val cards = koinInject<CardSearchBackend>()
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<Detail?>(null) }
    var mode by remember { mutableStateOf(Mode.WORDS) }
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ResultState>(ResultState.Idle) }

    val open = detail
    if (open != null) {
        BackHandler { detail = null }
        when (open) {
            is Detail.Word -> WordDetailScreen(open.seq, onBack = { detail = null })
            is Detail.Note -> NoteDetailScreen(open.noteId, onBack = { detail = null })
        }
        return
    }

    fun run(append: Boolean) {
        scope.launch {
            val q = query
            if (mode == Mode.WORDS) {
                val prev = state as? ResultState.Words
                val after = if (append) prev?.next else null
                if (!append) state = ResultState.Busy
                state = when (val r = words.search(q, after)) {
                    is WordSearchOutcome.Page ->
                        ResultState.Words((if (append) prev?.items.orEmpty() else emptyList()) + r.page.items, r.page.next)
                    is WordSearchOutcome.Failed -> ResultState.Failed(r.offline)
                }
            } else {
                state = ResultState.Busy
                state = when (val r = cards.search(q)) {
                    is CardSearchOutcome.Results -> ResultState.Cards(r.hits, PAGE, r.fromServer)
                    is CardSearchOutcome.ParseError -> ResultState.Invalid(r.errors)
                    is CardSearchOutcome.Failed -> ResultState.Failed(r.offline)
                }
            }
        }
    }

    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((m, label) in listOf(Mode.WORDS to R.string.search_mode_words, Mode.CARDS to R.string.search_mode_cards)) {
                    if (mode == m) {
                        Button(onClick = {}) { Text(T(label)) }
                    } else {
                        OutlinedButton(onClick = { mode = m; state = ResultState.Idle }) { Text(T(label)) }
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(T(if (mode == Mode.WORDS) R.string.search_hint_words else R.string.search_hint_cards)) },
            )
            Button(onClick = { run(append = false) }) { Text(T(R.string.search_go)) }
        }
        when (val s = state) {
            ResultState.Idle -> {}
            ResultState.Busy -> item { Text(T(R.string.word_loading)) }
            is ResultState.Failed -> item { Text(T(if (s.offline) R.string.search_needs_connection else R.string.search_failed)) }
            is ResultState.Invalid -> items(s.errors) { e ->
                Text(
                    T(errorRes(e.code)) + " " + T(R.string.search_error_position, e.span.start),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            is ResultState.Words -> {
                if (s.items.isEmpty()) item { Text(T(R.string.search_no_results)) }
                items(s.items, key = { it.seq }) { w ->
                    Column(Modifier.fillMaxWidth().clickable { detail = Detail.Word(w.seq) }.padding(vertical = 4.dp)) {
                        Text(w.headword, style = MaterialTheme.typography.titleMedium)
                        Text("${w.reading} ${w.gloss}".trim())
                        Text(T(statusRes(w.status)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.next != null) item { OutlinedButton(onClick = { run(append = true) }) { Text(T(R.string.search_more)) } }
            }
            is ResultState.Cards -> {
                if (s.fromServer) item { Text(T(R.string.search_from_server), style = MaterialTheme.typography.bodySmall) }
                if (s.hits.isEmpty()) item { Text(T(R.string.search_no_results)) }
                items(s.hits.take(s.shown), key = { it.cardId }) { c ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            detail = c.entrySeq?.let { Detail.Word(it) } ?: Detail.Note(c.noteId)
                        }.padding(vertical = 4.dp),
                    ) {
                        Text(c.title.ifEmpty { c.kind }, style = MaterialTheme.typography.titleMedium)
                        val suspended = if (c.suspended) " · " + T(R.string.search_card_suspended) else ""
                        Text(T(R.string.search_card_line, c.kind, T(cardStateRes(c.state))) + suspended, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (s.hits.size > s.shown) {
                    item { OutlinedButton(onClick = { state = s.copy(shown = s.shown + PAGE) }) { Text(T(R.string.search_more)) } }
                }
            }
        }
    }
}

private const val PAGE = 50

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
