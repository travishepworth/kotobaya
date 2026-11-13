package kotobaya.android.decks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.decks.DeckSummary
import kotobaya.android.decks.DeckWord
import kotobaya.android.decks.DecksIntents
import kotobaya.android.decks.DecksMode
import kotobaya.android.decks.DecksUiState
import kotobaya.android.i18n.T

/**
 * The Decks tab (read-only). [study] is the deck's study page (the review screen for the selected deck), shown
 * under a back button in [DecksMode.STUDY].
 */
@Composable
fun DecksScreen(state: DecksUiState, intents: DecksIntents, study: @Composable () -> Unit = {}) {
    when (state.mode) {
        DecksMode.LIST -> DeckList(state.decks, onRefresh = intents::refresh, onOpen = intents::open)
        DecksMode.DETAIL -> DeckDetail(state.deck, state.words, state.limit, intents)
        DecksMode.STUDY -> {
            BackHandler(onBack = intents::endStudy)
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = intents::endStudy) { Text(T(R.string.decks_back)) }
                study()
            }
        }
    }
}

@Composable
private fun typeLabel(type: String): String = T(
    when (type) {
        "media" -> R.string.decks_type_media
        "list" -> R.string.decks_type_list
        "smart" -> R.string.decks_type_smart
        else -> R.string.decks_type_user
    },
)

@Composable
private fun countsLine(d: DeckSummary): String =
    T(R.string.decks_counts, d.counts.due, d.counts.new, d.counts.learning, d.totalCards)

@Composable
private fun DeckList(decks: List<DeckSummary>?, onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(T(R.string.decks_title), Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text(T(R.string.decks_refresh)) }
        }
        when {
            decks == null -> Text(T(R.string.decks_loading))
            decks.isEmpty() -> Text(T(R.string.decks_empty))
            else -> LazyColumn {
                items(decks, key = { it.id }) { d ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(d.id) }.padding(vertical = 8.dp)) {
                        Text(d.name)
                        Text(typeLabel(d.type) + if (d.enabled) "" else " · " + T(R.string.decks_disabled))
                        Text(countsLine(d))
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckDetail(deck: DeckSummary?, words: List<DeckWord>?, limit: Int, intents: DecksIntents) {
    BackHandler(onBack = intents::back)
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        TextButton(onClick = intents::back) { Text(T(R.string.decks_back)) }
        if (deck == null) {
            Text(T(R.string.decks_loading))
            return@Column
        }
        Text(deck.name)
        Text(T(R.string.decks_detail_type, typeLabel(deck.type)))
        Text(countsLine(deck))
        if (deck.type == "smart") {
            deck.query?.let { Text(T(R.string.decks_detail_query, it)) }
            Text(T(R.string.decks_smart_note))
        }
        Button(onClick = intents::study) { Text(T(R.string.decks_study)) }
        Text(T(R.string.decks_words))
        if (words.isNullOrEmpty()) {
            if (words != null) Text(T(R.string.decks_words_empty))
        } else {
            LazyColumn {
                items(words, key = { it.itemKey }) { w -> Text(w.label, Modifier.padding(vertical = 2.dp)) }
                if (words.size >= limit) item {
                    TextButton(onClick = intents::more) { Text(T(R.string.decks_more)) }
                }
            }
        }
    }
}
