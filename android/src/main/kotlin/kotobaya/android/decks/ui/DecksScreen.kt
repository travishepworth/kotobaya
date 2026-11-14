package kotobaya.android.decks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.nav.DictJump
import kotobaya.android.decks.DeckSummary
import kotobaya.android.decks.DeckWord
import kotobaya.android.decks.DecksIntents
import kotobaya.android.decks.DecksMode
import kotobaya.android.decks.DecksUiState
import kotobaya.android.i18n.T
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonSize
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbDeckDot
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbIconButton
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSearchField
import kotobaya.android.ui.components.KbSectionTitle
import kotobaya.android.ui.components.KbSkeleton
import kotobaya.android.ui.components.KbSkeletonRows
import kotobaya.android.ui.components.KbTagChip
import kotobaya.android.ui.components.KbTone
import kotobaya.android.ui.components.KbTopBar
import kotobaya.android.ui.components.LARGE_TEXT
import kotobaya.android.ui.components.fontScale
import kotobaya.android.ui.theme.KbCardState
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbDeckColor
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.ja
import kotobaya.android.ui.theme.state

/** Above this many decks the list gets a filter field (DESIGN §5.5). */
private const val FILTER_AT = 12

/**
 * The Decks tab (DESIGN §5.5). Phones: the list, a deck pushes its page. From 840 dp: list and deck side by side.
 * [study] is the deck's review session, shown under a slim bar in [DecksMode.STUDY].
 */
@Composable
fun DecksScreen(state: DecksUiState, intents: DecksIntents, study: @Composable () -> Unit = {}) {
    if (state.mode == DecksMode.STUDY) {
        BackHandler(onBack = intents::endStudy)
        Column(Modifier.fillMaxSize().background(KbTheme.colors.bg)) {
            KbTopBar(state.deck?.name ?: T(R.string.decks_study), onBack = intents::endStudy, backLabel = T(R.string.decks_back))
            Box(Modifier.weight(1f)) { study() }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(KbTheme.colors.bg)) {
        val split = maxWidth >= KbTheme.breakpoint.expanded
        if (split) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(380.dp).fillMaxHeight()) { DeckList(state.decks, state.selectedId.takeIf { state.mode == DecksMode.DETAIL }, intents, state.loadFailed) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(KbTheme.colors.border))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.mode == DecksMode.DETAIL) {
                        DeckDetail(state.deck, state.words, state.limit, intents, showBack = false)
                    } else {
                        KbEmptyState(T(R.string.decks_select_title), body = T(R.string.decks_select_body), icon = KbIcons.Decks, page = true, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        } else if (state.mode == DecksMode.DETAIL) {
            BackHandler(onBack = intents::back)
            DeckDetail(state.deck, state.words, state.limit, intents, showBack = true)
        } else {
            DeckList(state.decks, null, intents, state.loadFailed)
        }
    }
}

@Composable
fun typeLabel(type: String): String = T(
    when (type) {
        "media" -> R.string.decks_type_media
        "list" -> R.string.decks_type_list
        "smart" -> R.string.decks_type_smart
        else -> R.string.decks_type_user
    },
)

@Composable
private fun groupLabel(type: String): String = T(
    when (type) {
        "media" -> R.string.decks_group_media
        "list" -> R.string.decks_group_list
        "smart" -> R.string.decks_group_smart
        else -> R.string.decks_group_user
    },
)

private val GROUP_ORDER = listOf("user", "smart", "list", "media")

/** A deck's work as words and colour: "12 due" (indigo) and "5 new" (state-new), or nothing when clear. */
@Composable
fun DeckCounts(d: DeckSummary, modifier: Modifier = Modifier) {
    val c = KbTheme.colors
    val due = d.counts.due + d.counts.learning
    Column(modifier, horizontalAlignment = if (fontScale() >= LARGE_TEXT) Alignment.Start else Alignment.End) {
        if (due > 0) Text(T(R.string.decks_due, due), style = KbTheme.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.primary)
        if (d.counts.new > 0) Text(T(R.string.decks_new, d.counts.new), style = KbTheme.type.caption, color = c.state(KbCardState.New).content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckList(decks: List<DeckSummary>?, selectedId: String?, intents: DecksIntents, failed: Boolean = false) {
    val c = KbTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(T(R.string.decks_title), style = KbTheme.type.headline, color = c.text, modifier = Modifier.weight(1f))
                KbIconButton(KbIcons.Refresh, T(R.string.decks_refresh), intents::refresh)
            }
        }
        when {
            decks == null && failed -> item {
                KbBanner(T(R.string.decks_load_failed), Modifier.padding(horizontal = 16.dp), kind = KbFeedback.Danger)
            }
            decks == null -> item { KbSkeletonRows(6, leading = true) }
            decks.isEmpty() -> item {
                KbEmptyState(T(R.string.decks_empty_title), body = T(R.string.decks_empty), icon = KbIcons.Decks, page = true)
            }
            else -> {
                if (decks.size > FILTER_AT) {
                    item {
                        KbSearchField(
                            filter, { filter = it }, label = T(R.string.decks_filter), clearLabel = T(R.string.search_clear), onSearch = {},
                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                        )
                    }
                }
                val shown = if (filter.isBlank()) decks else decks.filter { it.name.contains(filter.trim(), ignoreCase = true) }
                if (shown.isEmpty()) item { KbEmptyState(T(R.string.decks_filter_none, filter.trim()), icon = KbIcons.Search) }
                val groups = shown.groupBy { if (it.type in GROUP_ORDER) it.type else "user" }
                for (type in GROUP_ORDER) {
                    val rows = groups[type] ?: continue
                    stickyHeader(key = "h-$type") {
                        Row(Modifier.fillMaxWidth().background(c.bg).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                            KbOverline(groupLabel(type) + " · " + rows.size)
                        }
                    }
                    items(rows, key = { it.id }) { d ->
                        DeckRow(d, d.id == selectedId) { intents.open(d.id) }
                        KbDivider(inset = 42.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckRow(d: DeckSummary, selected: Boolean, onClick: () -> Unit) {
    KbListRow(
        title = d.name,
        supporting = if (d.enabled) T(R.string.decks_total, d.totalCards) else T(R.string.decks_total, d.totalCards) + " · " + T(R.string.decks_disabled),
        leading = { KbDeckDot(KbDeckColor.Hai) },
        trailing = { DeckCounts(d) },
        onClick = onClick,
        selected = selected,
    )
}

@Composable
private fun DeckDetail(deck: DeckSummary?, words: List<DeckWord>?, limit: Int, intents: DecksIntents, showBack: Boolean) {
    val c = KbTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            if (showBack) KbTopBar("", onBack = intents::back, backLabel = T(R.string.decks_back))
        }
        if (deck == null) {
            item {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KbSkeleton(Modifier.fillMaxWidth(0.6f), 30.dp)
                    KbSkeleton(Modifier.fillMaxWidth(), 88.dp)
                    KbSkeleton(Modifier.fillMaxWidth(), 56.dp)
                }
            }
            return@LazyColumn
        }
        item {
            Column(
                Modifier.widthIn(max = 760.dp).padding(horizontal = 20.dp).padding(top = if (showBack) 0.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(deck.name, style = KbTheme.type.headline, color = c.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KbTagChip(typeLabel(deck.type))
                        if (!deck.enabled) KbTagChip(T(R.string.decks_disabled))
                    }
                }
                StatTiles(deck)
                KbButton(T(R.string.decks_study), intents::study, Modifier.fillMaxWidth(), size = KbButtonSize.Lg, leading = KbIcons.Cards)
                if (deck.type == "smart") {
                    KbPanel(tone = KbTone.Sunken) {
                        KbOverline(T(R.string.decks_query_label))
                        deck.query?.let {
                            Text(it, style = KbTheme.type.bodySm.copy(fontFamily = FontFamily.Monospace), color = c.text, modifier = Modifier.padding(top = 6.dp))
                        }
                        Text(T(R.string.decks_smart_note), style = KbTheme.type.bodySm, color = c.textMuted, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                KbSectionTitle(T(R.string.decks_words), Modifier.padding(top = 8.dp))
            }
        }
        item {
            Box(Modifier.widthIn(max = 760.dp).padding(horizontal = 20.dp).padding(top = 8.dp)) {
                KbPanel(padding = PaddingValues(0.dp)) {
                    when {
                        words == null -> KbSkeletonRows(4)
                        words.isEmpty() -> KbEmptyState(T(R.string.decks_words_empty), icon = KbIcons.Book)
                        else -> words.forEachIndexed { i, w ->
                            if (i > 0) KbDivider(inset = 16.dp)
                            KbListRow(
                                w.label,
                                titleStyle = KbTheme.type.jpBody.copy(lineHeight = KbTheme.type.titleSm.lineHeight).ja(),
                                titleMaxLines = 2,
                                trailing = w.entrySeq?.let { seq -> { KbIconButton(KbIcons.Book, T(R.string.dict_open), { DictJump.openWord(seq) }) } },
                            )
                        }
                    }
                }
            }
        }
        if (words != null && words.size >= limit) {
            item {
                Box(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    KbButton(T(R.string.decks_more), intents::more, variant = KbButtonVariant.Outline)
                }
            }
        }
    }
}

/** Due / New / Learning / Cards as four tiles (2 × 2 when narrow or at large text). */
@Composable
private fun StatTiles(d: DeckSummary) {
    val tiles = listOf(
        T(R.string.decks_stat_due) to d.counts.due,
        T(R.string.decks_stat_new) to d.counts.new,
        T(R.string.decks_stat_learning) to d.counts.learning,
        T(R.string.decks_stat_cards) to d.totalCards,
    )
    BoxWithConstraints {
        val rows = if (fontScale() >= LARGE_TEXT || maxWidth < 420.dp) tiles.chunked(2) else listOf(tiles)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { i, (label, n) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(KbTheme.radius.md)).background(KbTheme.colors.surfaceSunken).padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(n.toString(), style = KbTheme.type.display, color = if (label == tiles[0].first && n > 0) KbTheme.colors.primary else KbTheme.colors.text)
                            Text(label, style = KbTheme.type.bodySm, color = KbTheme.colors.textMuted)
                        }
                    }
                }
            }
        }
    }
}
