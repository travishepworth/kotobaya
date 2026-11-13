package kotobaya.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.kbTween

/**
 * Segmented control for 2–4 peer views (DESIGN §3): a sunken track with a raised thumb on the selected option,
 * radio-group semantics. Labels wrap instead of clipping; at large text the options stack vertically (stock M3
 * segmented buttons clip at 200 %).
 */
@Composable
fun <T> KbSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KbTheme.colors
    val stacked = fontScale() >= LARGE_TEXT && options.size > 2
    val track = Modifier
        .clip(RoundedCornerShape(KbRadius.md))
        .background(c.surfaceSunken)
        .padding(3.dp)
        .selectableGroup()
    if (stacked) {
        Column(modifier.fillMaxWidth().then(track), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (value, label) -> Segment(label, value == selected, Modifier.fillMaxWidth()) { onSelect(value) } }
        }
    } else {
        Row(modifier.height(IntrinsicSize.Min).then(track), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (value, label) ->
                Segment(label, value == selected, Modifier.weight(1f).fillMaxHeight()) { onSelect(value) }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = KbTheme.colors
    val bg by animateColorAsState(if (selected) c.surfaceRaised else Color.Transparent, kbTween(KbMotion.FAST), label = "seg")
    val fg = if (selected) c.text else c.textMuted
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KbRadius.sm + 1.dp)
    Box(
        modifier
            .heightIn(min = 42.dp)
            .kbFocusRing(interaction, KbRadius.sm)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.background(Color.Transparent) else Modifier)
            .selectable(selected, interaction, ripple(), role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = KbTheme.type.label.copy(fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else KbTheme.type.label.fontWeight),
            color = fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Tabs for sections of one object (DESIGN §3): label + optional count, an indigo underline on the selected tab.
 * Tabs share the width; labels wrap (never clip) at large text.
 */
@Composable
fun <T> KbTabs(tabs: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = KbTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            tabs.forEach { (value, label) ->
                val on = value == selected
                val interaction = remember { MutableInteractionSource() }
                val line by animateColorAsState(if (on) c.primary else Color.Transparent, kbTween(KbMotion.BASE), label = "tab")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .heightIn(min = 48.dp)
                        .selectable(on, interaction, ripple(), role = Role.Tab) { onSelect(value) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = KbTheme.type.label,
                            color = if (on) c.text else c.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(3.dp).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(line))
                }
            }
        }
        KbDivider()
    }
}
