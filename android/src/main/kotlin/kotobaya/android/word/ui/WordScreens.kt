package kotobaya.android.word.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.search.ui.cardStateRes
import kotobaya.android.search.ui.statusRes
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbEmptyTone
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSkeleton
import kotobaya.android.ui.components.KbSkeletonRows
import kotobaya.android.ui.components.KbStateChip
import kotobaya.android.ui.components.KbTabs
import kotobaya.android.ui.components.KbTagChip
import kotobaya.android.ui.components.KbTone
import kotobaya.android.ui.components.KbTopBar
import kotobaya.android.ui.components.cardStateOf
import kotobaya.android.ui.components.jaPhrase
import kotobaya.android.ui.components.jaTitle
import kotobaya.android.ui.components.wordStatusTone
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.ja
import kotobaya.android.word.NoteLoad
import kotobaya.android.word.NoteView
import kotobaya.android.word.WordDetail
import kotobaya.android.word.WordLoad

private enum class WordTab { SENSES, KANJI, CARDS }

/** The reading measure of entry pages (DESIGN §2.3). */
private val MEASURE = 680.dp

/**
 * A dictionary entry (DESIGN §5.3): headword in jp-display, reading and status, then tabs Senses · Kanji · My cards.
 * Senses are numbered with the part of speech above the glosses. Kanji are tiles; a tile searches for words using it
 * ([onKanji]). [onBack] null hides the back button (list–detail).
 */
@Composable
fun WordDetailScreen(load: WordLoad?, onBack: (() -> Unit)?, onKanji: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) KbTopBar("", onBack = onBack, backLabel = T(R.string.word_back))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = if (onBack == null) 24.dp else 0.dp, bottom = 32.dp),
        ) {
            Column(Modifier.widthIn(max = MEASURE).fillMaxWidth()) {
                when (load) {
                    null -> WordSkeleton()
                    WordLoad.NotFound -> KbEmptyState(T(R.string.word_not_found), icon = KbIcons.Search, page = true)
                    is WordLoad.Failed -> KbEmptyState(
                        T(if (load.offline) R.string.word_needs_connection else R.string.word_failed),
                        icon = if (load.offline) KbIcons.Offline else KbIcons.Alert,
                        tone = KbEmptyTone.Error,
                        page = true,
                    )
                    is WordLoad.Found -> WordBody(load.detail, onKanji)
                }
            }
        }
    }
}

@Composable
private fun WordSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        KbSkeleton(Modifier.size(width = 180.dp, height = 52.dp), radius = KbRadius.sm)
        KbSkeleton(Modifier.size(width = 120.dp, height = 20.dp))
        KbSkeletonRows(4)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordBody(d: WordDetail, onKanji: (String) -> Unit) {
    val c = KbTheme.colors
    var tab by rememberSaveable(d.seq) { mutableStateOf(WordTab.SENSES) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(d.headword, style = KbTheme.type.jpDisplay.jaPhrase(), color = c.text)
        if (d.reading.isNotEmpty() && d.reading != d.headword) {
            Text(d.reading, style = KbTheme.type.reading, color = c.textMuted)
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KbStateChip(wordStatusTone(d.status), T(statusRes(d.status)))
            if (d.notes.isNotEmpty()) KbTagChip(T(R.string.word_in_your_cards))
        }
        if (d.retired) KbBanner(T(R.string.word_retired), Modifier.padding(top = 8.dp), kind = KbFeedback.Warning)
        val others = (d.kanjiForms + d.readings).filter { it != d.headword && it != d.reading }.distinct()
        if (others.isNotEmpty()) {
            Text(
                T(R.string.word_also, others.joinToString("、")),
                style = KbTheme.type.bodySm.ja(),
                color = c.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    val tabs = buildList {
        add(WordTab.SENSES to T(R.string.word_tab_senses, d.senses.size))
        if (d.kanji.isNotEmpty()) add(WordTab.KANJI to T(R.string.word_tab_kanji, d.kanji.size))
        add(WordTab.CARDS to T(R.string.word_tab_cards, d.notes.sumOf { it.cards.size }))
    }
    KbTabs(tabs, tab, { tab = it }, Modifier.padding(top = 24.dp))
    Column(Modifier.padding(top = 16.dp)) {
        when (tab) {
            WordTab.SENSES -> Senses(d)
            WordTab.KANJI -> FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                d.kanji.forEach { k -> KanjiTile(k) { onKanji(k) } }
            }
            WordTab.CARDS -> if (d.notes.isEmpty()) {
                KbEmptyState(T(R.string.word_no_notes), icon = KbIcons.Cards)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { d.notes.forEach { NoteBlock(it) } }
            }
        }
    }
}

@Composable
private fun Senses(d: WordDetail) {
    val c = KbTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        d.senses.forEachIndexed { i, s ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.surfaceSunken), contentAlignment = Alignment.Center) {
                    Text((i + 1).toString(), style = KbTheme.type.caption, color = c.textMuted)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (s.pos.isNotEmpty()) Text(s.pos.joinToString(" · "), style = KbTheme.type.caption, color = c.textMuted)
                    Text(s.glosses.joinToString("; "), style = KbTheme.type.bodyLg, color = c.text)
                }
            }
        }
    }
}

/** A kanji tile (serif literal); tap searches for words that use it. */
@Composable
private fun KanjiTile(literal: String, onClick: () -> Unit) {
    val c = KbTheme.colors
    val label = T(R.string.word_kanji_search, literal)
    Box(
        Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(KbRadius.lg))
            .background(c.surfaceSunken)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(literal, style = KbTheme.type.jpHero.copy(fontSize = 48.sp, lineHeight = 56.sp), color = c.text, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NoteBlock(n: NoteView) {
    KbPanel(padding = PaddingValues(0.dp)) {
        if (n.title.isNotEmpty()) {
            Text(n.title, style = jaTitle(), color = KbTheme.colors.text, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
        }
        n.cards.forEachIndexed { i, card ->
            if (i > 0) KbDivider(inset = 16.dp)
            KbListRow(
                title = card.kind.replaceFirstChar { it.uppercase() },
                titleStyle = KbTheme.type.body,
                trailing = {
                    KbStateChip(
                        cardStateOf(card.state, card.suspended),
                        if (card.suspended) T(R.string.search_card_suspended) else T(cardStateRes(card.state)),
                    )
                },
            )
        }
    }
}

/** A note without a dictionary entry (reached from a card result). [load] null while loading. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(load: NoteLoad?, onBack: (() -> Unit)?) {
    val c = KbTheme.colors
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) KbTopBar(T(R.string.note_title), onBack = onBack, backLabel = T(R.string.word_back))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = if (onBack == null) 24.dp else 0.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.widthIn(max = MEASURE).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (load) {
                    null -> WordSkeleton()
                    NoteLoad.Unavailable -> KbEmptyState(T(R.string.note_unavailable), icon = KbIcons.Sync, page = true)
                    is NoteLoad.Found -> {
                        Text(load.note.title.ifEmpty { T(R.string.note_title) }, style = KbTheme.type.jpDisplay.jaPhrase(), color = c.text)
                        if (load.tags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                load.tags.forEach { KbTagChip(it) }
                            }
                        }
                        if (load.mnemonic.isNotEmpty()) {
                            KbPanel(tone = KbTone.Sunken) {
                                KbOverline(T(R.string.note_mnemonic_label))
                                Text(load.mnemonic, style = KbTheme.type.body.ja(), color = c.text, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        NoteBlock(load.note.copy(title = ""))
                    }
                }
            }
        }
    }
}
