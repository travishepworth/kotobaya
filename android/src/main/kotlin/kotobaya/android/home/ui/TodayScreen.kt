package kotobaya.android.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotobaya.android.R
import kotobaya.android.decks.DeckSummary
import kotobaya.android.decks.ui.DeckCounts
import kotobaya.android.decks.ui.typeLabel
import kotobaya.android.i18n.T
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonSize
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbDeckDot
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbIconTile
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSectionTitle
import kotobaya.android.ui.components.KbSkeleton
import kotobaya.android.ui.components.KbSkeletonRows
import kotobaya.android.ui.components.KbTone
import kotobaya.android.ui.theme.KbDeckColor
import kotobaya.android.ui.theme.KbTheme

/** What Today can ask for. */
interface TodayIntents {
    fun startReview() {}
    fun studyDeck(deckId: String) {}
    fun openDecks() {}
    fun openStruggle() {}
    fun openSearch() {}
    fun pair() {}
}

/** Decks shown on Today (the ones with work, in deck order). */
private const val TODAY_DECKS = 6

/**
 * Today (DESIGN §5.2): today at a glance. A hero panel with Start review in thumb reach, the decks with work, the
 * Struggle entry and the sync status. One column under 840 dp, two from 840 dp. [decks] null while loading;
 * [statusChips] is the sync chip slot.
 */
@Composable
fun TodayScreen(
    unpaired: Boolean,
    decks: List<DeckSummary>?,
    statusChips: List<@Composable () -> Unit>,
    intents: TodayIntents,
    nowMs: Long = System.currentTimeMillis(),
) {
    val c = KbTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val two = maxWidth >= KbTheme.breakpoint.expanded
        val pad = if (maxWidth >= KbTheme.breakpoint.medium) 32.dp else 16.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pad, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = KbTheme.size.content).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val date = remember(nowMs) { DateFormat.getDateInstance(DateFormat.FULL).format(Date(nowMs)) }
                Column {
                    KbOverline(date)
                    Text(T(R.string.today_title), style = KbTheme.type.headline, color = c.text)
                }
                if (unpaired) PairPanel(intents)
                if (decks != null && decks.isEmpty()) {
                    KbPanel(padding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                        KbEmptyState(
                            T(R.string.today_empty_title),
                            body = T(R.string.today_empty_body),
                            icon = KbIcons.Book,
                            page = true,
                            action = { KbButton(T(R.string.today_empty_action), intents::openSearch, leading = KbIcons.Search) },
                        )
                    }
                } else if (two) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Hero(decks, intents)
                            DecksPanel(decks, intents)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            StrugglePanel(intents)
                            SyncPanel(statusChips)
                        }
                    }
                } else {
                    Hero(decks, intents)
                    DecksPanel(decks, intents)
                    StrugglePanel(intents)
                    SyncPanel(statusChips)
                }
            }
        }
    }
}

@Composable
private fun PairPanel(intents: TodayIntents) {
    KbPanel(tone = KbTone.Surface) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KbIconTile(KbIcons.Device, container = KbTheme.colors.warningContainer, tint = KbTheme.colors.onWarningContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(T(R.string.home_pair_prompt), style = KbTheme.type.body, color = KbTheme.colors.text)
                KbButton(T(R.string.home_pair_action), intents::pair, variant = KbButtonVariant.Tonal)
            }
        }
    }
}

/** The hero: what is waiting, and the one primary action of the screen. */
@Composable
private fun Hero(decks: List<DeckSummary>?, intents: TodayIntents) {
    val c = KbTheme.colors
    KbPanel(padding = androidx.compose.foundation.layout.PaddingValues(20.dp), elevation = 1.dp) {
        if (decks == null) {
            KbSkeleton(Modifier.fillMaxWidth(0.5f), 28.dp)
            KbSkeleton(Modifier.padding(top = 12.dp).fillMaxWidth(0.7f), 16.dp)
        } else {
            val waiting = decks.filter { it.enabled && it.counts.due + it.counts.learning + it.counts.new > 0 }
            Text(
                T(if (waiting.isEmpty()) R.string.today_hero_clear else R.string.today_hero_title),
                style = KbTheme.type.title,
                color = c.text,
            )
            Text(
                if (waiting.isEmpty()) T(R.string.today_hero_nothing) else pluralStringResource(R.plurals.today_hero_waiting, waiting.size, waiting.size),
                style = KbTheme.type.body,
                color = c.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        KbButton(
            T(R.string.today_start_review),
            intents::startReview,
            Modifier.padding(top = 20.dp).fillMaxWidth(),
            size = KbButtonSize.Lg,
            leading = KbIcons.Cards,
            enabled = decks != null,
        )
    }
}

@Composable
private fun DecksPanel(decks: List<DeckSummary>?, intents: TodayIntents) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KbSectionTitle(T(R.string.today_decks)) {
            KbButton(T(R.string.today_all_decks), intents::openDecks, variant = KbButtonVariant.Quiet)
        }
        KbPanel(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            if (decks == null) {
                KbSkeletonRows(4, leading = true)
                return@KbPanel
            }
            val active = decks.filter { it.enabled && it.counts.due + it.counts.learning + it.counts.new > 0 }.take(TODAY_DECKS)
            if (active.isEmpty()) {
                KbEmptyState(T(R.string.today_decks_clear), tone = kotobaya.android.ui.components.KbEmptyTone.Clear)
                return@KbPanel
            }
            active.forEachIndexed { i, d ->
                if (i > 0) KbDivider(inset = 42.dp)
                KbListRow(
                    title = d.name,
                    supporting = if (d.type == "user") null else typeLabel(d.type),
                    leading = { KbDeckDot(KbDeckColor.Hai) },
                    trailing = { DeckCounts(d) },
                    onClick = { intents.studyDeck(d.id) },
                )
            }
        }
    }
}

@Composable
private fun StrugglePanel(intents: TodayIntents) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KbSectionTitle(T(R.string.nav_struggle))
        KbPanel(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            KbListRow(
                title = T(R.string.today_struggle_title),
                supporting = T(R.string.today_struggle_body),
                leading = { KbIconTile(KbIcons.Flame, container = KbTheme.colors.dangerContainer, tint = KbTheme.colors.onDangerContainer) },
                onClick = intents::openStruggle,
            )
        }
    }
}

@Composable
private fun SyncPanel(statusChips: List<@Composable () -> Unit>) {
    if (statusChips.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KbSectionTitle(T(R.string.sync_title))
        KbPanel(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Column { statusChips.forEach { it() } } }
    }
}
