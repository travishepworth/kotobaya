package kotobaya.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbCardState
import kotobaya.android.ui.theme.KbDeckColor
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.KbTone as ToneColors
import kotobaya.android.ui.theme.deck
import kotobaya.android.ui.theme.state

/** A static chip: [tone] colours, optional [glyph] before the [text] (colour is never the only signal). */
@Composable
fun KbChip(text: String, tone: ToneColors, modifier: Modifier = Modifier, glyph: String? = null) {
    Row(
        modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(KbRadius.sm))
            .background(tone.container)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (glyph != null) Text(glyph, style = KbTheme.type.caption, color = tone.content)
        Text(text, style = KbTheme.type.label.copy(fontSize = KbTheme.type.caption.fontSize), color = tone.content, maxLines = 1)
    }
}

/** A card-state chip: state colour + glyph + word. */
@Composable
fun KbStateChip(state: KbCardState, text: String, modifier: Modifier = Modifier) {
    KbChip(text, KbTheme.colors.state(state), modifier, glyph = state.glyph)
}

/** A neutral chip (tags, counts, types). */
@Composable
fun KbTagChip(text: String, modifier: Modifier = Modifier) {
    val c = KbTheme.colors
    Box(
        modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(KbRadius.sm))
            .border(1.dp, c.border, RoundedCornerShape(KbRadius.sm))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = KbTheme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The deck colour dot that sits next to a deck name. */
@Composable
fun KbDeckDot(color: KbDeckColor, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(modifier.size(size).clip(CircleShape).background(KbTheme.colors.deck(color).content))
}

/**
 * A filter chip (toggle): 32 dp visual inside a 48 dp hit area, check glyph when on. Announced as a checkbox-like
 * toggle.
 */
@Composable
fun KbFilterChip(text: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = KbTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KbRadius.sm)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .toggleable(selected, interaction, null, role = Role.Checkbox) { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .kbFocusRing(interaction, KbRadius.sm)
                .heightIn(min = 32.dp)
                .clip(shape)
                .background(if (selected) c.primaryContainer else Color.Transparent)
                .border(1.dp, if (selected) c.primaryContainer else c.borderStrong, shape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) Icon(KbIcons.Check, null, tint = c.onPrimaryContainer, modifier = Modifier.size(16.dp))
            Text(text, style = KbTheme.type.label, color = if (selected) c.onPrimaryContainer else c.text)
        }
    }
}

/** A tappable suggestion chip (recent searches, kanji). 48 dp hit area. */
@Composable
fun KbSuggestionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, japanese: Boolean = false) {
    val c = KbTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KbRadius.sm)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .kbFocusRing(interaction, KbRadius.sm)
            .clip(shape)
            .background(c.surfaceSunken)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (japanese) KbTheme.type.jpBody.copy(lineHeight = KbTheme.type.body.lineHeight) else KbTheme.type.label, color = c.text)
    }
}
