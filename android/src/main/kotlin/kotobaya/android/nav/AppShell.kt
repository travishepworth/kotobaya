package kotobaya.android.nav

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.kbFocusRing
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.kbTween

/** The five Android destinations (DESIGN §4.2). Media is gone (I-5); Struggle is reached from Today. */
enum class Tab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    TODAY(Routes.HOME, R.string.nav_home, KbIcons.Today),
    REVIEW(Routes.REVIEW, R.string.nav_review, KbIcons.Cards),
    SEARCH(Routes.SEARCH, R.string.nav_search, KbIcons.Search),
    DECKS(Routes.DECKS, R.string.nav_decks, KbIcons.Decks),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, KbIcons.Settings),
    ;

    companion object {
        /** The tab a route belongs to (pushed pages keep their tab highlighted); null for none. */
        fun of(route: String?): Tab? = when {
            route == null -> null
            route.startsWith(Routes.REVIEW) -> REVIEW
            route.startsWith(Routes.SETTINGS) -> SETTINGS
            route == kotobaya.android.struggle.STRUGGLE_ROUTE -> TODAY
            else -> entries.firstOrNull { it.route == route }
        }
    }
}

/** Test tag of a tab's nav item (bottom bar or rail). */
fun navTag(tab: Tab): String = "nav-${tab.route}"

/**
 * The app shell: a bottom bar under 600 dp, a navigation rail (with the 言 seal) from 600 dp. [immersive] hides the
 * bottom bar (Review on phones); the rail stays on larger screens.
 */
@Composable
fun AppShell(selected: Tab?, onSelect: (Tab) -> Unit, immersive: Boolean = false, content: @Composable () -> Unit) {
    val c = KbTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        if (maxWidth < KbTheme.breakpoint.medium) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (!immersive) BottomBar(selected, onSelect)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Rail(selected, onSelect)
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

/** Nav labels follow the font scale but stop growing at 1.3× so five labels always fit one line. */
@Composable
private fun navLabelStyle() = KbTheme.type.label.let { s ->
    val scale = LocalDensity.current.fontScale
    if (scale > MAX_NAV_SCALE) s.copy(fontSize = (s.fontSize.value * MAX_NAV_SCALE / scale).sp, lineHeight = (s.lineHeight.value * MAX_NAV_SCALE / scale).sp) else s
}

private const val MAX_NAV_SCALE = 1.3f

@Composable
private fun BottomBar(selected: Tab?, onSelect: (Tab) -> Unit) {
    val c = KbTheme.colors
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().heightIn(min = KbTheme.size.bottomNav).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            Tab.entries.forEach { tab -> NavItem(tab, tab == selected, Modifier.weight(1f)) { onSelect(tab) } }
        }
    }
}

@Composable
private fun Rail(selected: Tab?, onSelect: (Tab) -> Unit) {
    val c = KbTheme.colors
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(KbTheme.size.rail + 8.dp).fillMaxHeight().background(c.surface).padding(vertical = 12.dp).selectableGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Seal(Modifier.padding(bottom = 20.dp))
            Tab.entries.forEach { tab -> NavItem(tab, tab == selected, Modifier.fillMaxWidth()) { onSelect(tab) } }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
    }
}

/** The brand seal: 言 in vermilion serif on a pale wash (DESIGN §3 App shell). */
@Composable
fun Seal(modifier: Modifier = Modifier) {
    val c = KbTheme.colors
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(KbRadius.md)).background(c.dangerContainer).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text("言", style = KbTheme.type.jpHero.copy(fontSize = 28.sp, lineHeight = 32.sp), color = c.brand)
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = KbTheme.colors
    val pill by animateColorAsState(if (selected) c.primaryContainer else Color.Transparent, kbTween(KbMotion.FAST))
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .testTag(navTag(tab))
            .heightIn(min = 64.dp)
            .selectable(selected, interaction, ripple(bounded = false, radius = 36.dp), role = Role.Tab, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier
                .kbFocusRing(interaction, 16.dp)
                .widthIn(min = 56.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(pill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.icon, null, tint = if (selected) c.onPrimaryContainer else c.textMuted, modifier = Modifier.size(24.dp))
        }
        Text(
            T(tab.labelRes),
            style = navLabelStyle(),
            color = if (selected) c.text else c.textMuted,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}
