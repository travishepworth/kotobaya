package kotobaya.android.search.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.search.AddNoteOutcome
import kotobaya.android.search.CardHit
import kotobaya.android.search.DeckSheetMode
import kotobaya.android.search.SearchDetail
import kotobaya.android.search.SearchIntents
import kotobaya.android.search.SearchResults
import kotobaya.android.search.SearchUiState
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbDeckPicker
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbEmptyTone
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbSearchField
import kotobaya.android.ui.components.KbSkeletonRows
import kotobaya.android.ui.components.KbStateChip
import kotobaya.android.ui.components.KbSuggestionChip
import kotobaya.android.ui.components.cardStateOf
import kotobaya.android.ui.components.jaTitle
import kotobaya.android.ui.components.wordStatusTone
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.word.NoteLoad
import kotobaya.android.word.WordLoad
import kotobaya.android.word.ui.NoteDetailScreen
import kotobaya.android.word.ui.WordDetailScreen
import kotobaya.contracts.search.SearchErrorCode

/** Example dictionary queries for the empty state (DESIGN §5.3: "Try 取る, toru, take"). */
private val WORD_EXAMPLES = listOf("取る", "toru", "take")

/** Example card queries (the query language from core). */
private val CARD_EXAMPLES = listOf("is:due", "lapses>3", "is:suspended")

/**
 * Search (DESIGN §5.3): dictionary words and the user's cards in one place. Phones: results, a result pushes its
 * word page. From 840 dp: results and the word page side by side (40/60).
 */
@Composable
fun SearchScreen(state: SearchUiState, intents: SearchIntents) {
    val open = state.detail
    // AND-BACK (ADR-030): back closes the deck sheet, then the detail, then clears the query, then leaves.
    BackHandler(enabled = state.sheet != null || open != null || state.query.isNotEmpty() || state.results != SearchResults.Idle) {
        when {
            state.sheet != null -> intents.closeDeckSheet()
            open != null -> intents.closeDetail()
            else -> intents.clearQuery()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(KbTheme.colors.bg)) {
        val listWidth = (maxWidth * 0.4f).coerceAtLeast(360.dp)
        if (maxWidth >= KbTheme.breakpoint.expanded) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) { ResultsPane(state, intents) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(KbTheme.colors.border))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (open != null) {
                        Detail(open, state, intents, showBack = false)
                    } else {
                        KbEmptyState(
                            T(R.string.search_pick_title), body = T(R.string.search_pick_body), icon = KbIcons.Book, page = true,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        } else if (open != null) {
            Detail(open, state, intents, showBack = true)
        } else {
            ResultsPane(state, intents)
        }
    }
}

@Composable
private fun Detail(open: SearchDetail, state: SearchUiState, intents: SearchIntents, showBack: Boolean) {
    val onBack = if (showBack) intents::closeDetail else null
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (open) {
                is SearchDetail.Word -> WordDetailScreen(open.load, onBack = onBack, onKanji = { k ->
                    intents.closeDetail()
                    intents.setQuery(k)
                    intents.search()
                })
                is SearchDetail.Note -> NoteDetailScreen(open.load, onBack = onBack)
            }
        }
        DeckActions(open, state, intents)
    }
    DeckSheetHost(state, intents)
}

/** AND-ADD2DECK: "Add to my notes" (a word with no note) and "Add to deck" under the detail, with the decks it is in. */
@Composable
private fun DeckActions(open: SearchDetail, state: SearchUiState, intents: SearchIntents) {
    val c = KbTheme.colors
    val word = ((open as? SearchDetail.Word)?.load as? WordLoad.Found)?.detail
    val ready = word != null || ((open as? SearchDetail.Note)?.load is NoteLoad.Found)
    if (!ready) return
    val hasNote = word == null || word.notes.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (word != null && hasNote) Text(T(R.string.notes_in_mine), style = KbTheme.type.label, color = c.textMuted)
        if (state.detailDecks.isNotEmpty()) {
            Text(T(R.string.notes_in_decks, state.detailDecks.joinToString(", ")), style = KbTheme.type.bodySm, color = c.textMuted)
        }
        state.addNote?.let { outcome ->
            val (text, kind) = when (outcome) {
                AddNoteOutcome.Added -> R.string.notes_added to KbFeedback.Success
                AddNoteOutcome.Duplicate -> R.string.notes_duplicate to KbFeedback.Info
                AddNoteOutcome.Failed -> R.string.notes_add_failed to KbFeedback.Danger
            }
            KbBanner(T(text), kind = kind)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (word != null && !hasNote) {
                KbButton(T(R.string.notes_add), { intents.openAddNoteSheet(word.headword, word.reading) }, Modifier.weight(1f))
            }
            KbButton(
                T(R.string.deck_add), intents::openDeckSheet, Modifier.weight(1f),
                variant = if (word != null && !hasNote) KbButtonVariant.Tonal else KbButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun DeckSheetHost(state: SearchUiState, intents: SearchIntents) {
    val sheet = state.sheet ?: return
    when (sheet.mode) {
        DeckSheetMode.TOGGLE -> KbDeckPicker(
            T(R.string.deck_picker_title), sheet.decks, { intents.toggleDeck(it.id) }, intents::createDeck, intents::closeDeckSheet,
        )
        DeckSheetMode.ADD_NOTE -> KbDeckPicker(
            T(R.string.notes_add_title), sheet.decks, { intents.addNote(it.id) }, intents::createDeck, intents::closeDeckSheet,
            onNone = { intents.addNote(null) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsPane(state: SearchUiState, intents: SearchIntents) {
    val c = KbTheme.colors
    val selected = (state.detail as? SearchDetail.Word)?.seq
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(T(R.string.nav_search), style = KbTheme.type.headline, color = c.text)
                KbSearchField(
                    state.query,
                    intents::setQuery,
                    label = T(R.string.search_hint),
                    clearLabel = T(R.string.search_clear),
                    onSearch = intents::search,
                )
            }
        }
        when (val s = state.results) {
            SearchResults.Idle -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    KbEmptyState(
                        T(R.string.search_idle_title),
                        body = T(R.string.search_idle_body),
                        icon = KbIcons.Search,
                    )
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        (WORD_EXAMPLES + CARD_EXAMPLES).forEach { ex ->
                            KbSuggestionChip(ex, { intents.setQuery(ex); intents.search() }, japanese = ex in WORD_EXAMPLES)
                        }
                    }
                }
            }
            SearchResults.Busy -> item { KbSkeletonRows(6) }
            is SearchResults.Failed -> item {
                KbEmptyState(
                    T(if (s.offline) R.string.search_needs_connection else R.string.search_failed),
                    icon = if (s.offline) KbIcons.Offline else KbIcons.Alert,
                    tone = KbEmptyTone.Error,
                    action = { KbButton(T(R.string.search_retry), intents::search, variant = KbButtonVariant.Tonal) },
                )
            }
            is SearchResults.Invalid -> item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.errors.forEach { e ->
                        KbBanner(T(errorRes(e.code)) + " " + T(R.string.search_error_position, e.span.start), kind = KbFeedback.Danger)
                    }
                }
            }
            is SearchResults.Merged -> {
                if (s.words.isEmpty() && s.notes.isEmpty()) {
                    item { KbEmptyState(T(R.string.search_no_results), body = T(R.string.search_no_results_words), icon = KbIcons.Search) }
                }
                itemsIndexed(s.words, key = { _, w -> "w${w.hit.seq}" }) { i, row ->
                    val w = row.hit
                    if (i > 0) KbDivider(inset = 16.dp)
                    KbListRow(
                        title = w.headword,
                        titleStyle = jaTitle(),
                        supporting = listOf(w.reading, w.gloss).filter { it.isNotBlank() }.joinToString(" · "),
                        trailing = {
                            Column(horizontalAlignment = Alignment.End) {
                                if (row.mine != null) {
                                    KbStateChip(wordStatusTone(w.status), T(R.string.search_in_my_cards))
                                } else if (w.status != "new") {
                                    KbStateChip(wordStatusTone(w.status), T(statusRes(w.status)))
                                }
                                val decks = row.mine?.deckNames.orEmpty()
                                if (decks.isNotEmpty()) Text(decks.joinToString(", "), style = KbTheme.type.bodySm, color = c.textMuted, maxLines = 1)
                            }
                        },
                        onClick = { intents.openWord(w.seq) },
                        selected = w.seq == selected,
                    )
                }
                if (s.next != null) item { MoreButton(intents::moreWords) }
                if (s.notes.isNotEmpty()) {
                    item {
                        Text(
                            T(R.string.search_your_notes), style = KbTheme.type.label, color = c.textMuted,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(s.notes, key = { _, n -> "n${n.noteId}" }) { i, hit ->
                        if (i > 0) KbDivider(inset = 16.dp)
                        CardRow(hit, intents)
                    }
                }
            }
            is SearchResults.Cards -> {
                if (s.fromServer) item { KbBanner(T(R.string.search_from_server), Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                if (s.hits.isEmpty()) {
                    item { KbEmptyState(T(R.string.search_no_results), body = T(R.string.search_no_results_cards), icon = KbIcons.Search) }
                }
                itemsIndexed(s.hits.take(s.shown), key = { _, c -> c.cardId }) { i, hit ->
                    if (i > 0) KbDivider(inset = 16.dp)
                    CardRow(hit, intents)
                }
                if (s.hits.size > s.shown) item { MoreButton(intents::moreCards) }
            }
        }
    }
}

/** A card result: its word, then reading and meaning (never the card kind). */
@Composable
private fun CardRow(hit: CardHit, intents: SearchIntents) {
    KbListRow(
        title = hit.title,
        titleStyle = jaTitle(),
        supporting = listOf(hit.reading, hit.meaning).filter { it.isNotBlank() }.joinToString(" · "),
        trailing = { KbStateChip(cardStateOf(hit.state, hit.suspended), if (hit.suspended) T(R.string.search_card_suspended) else T(cardStateRes(hit.state))) },
        onClick = {
            val seq = hit.entrySeq
            if (seq != null) intents.openWord(seq) else intents.openNote(hit.noteId)
        },
    )
}

@Composable
private fun MoreButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        KbButton(T(R.string.search_more), onClick, variant = KbButtonVariant.Outline)
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
