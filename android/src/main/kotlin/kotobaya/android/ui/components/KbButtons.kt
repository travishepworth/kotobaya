package kotobaya.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.kbTween

/** Button emphasis (DESIGN §3 Button). One [Primary] per view; [Danger] always names what it deletes. */
enum class KbButtonVariant { Primary, Tonal, Outline, Quiet, Danger }

/** Md is the 48 dp control; Lg (56 dp) is the one big action of a screen (Show answer, Start review). */
enum class KbButtonSize { Md, Lg }

private data class ButtonColors(val bg: Color, val fg: Color, val border: Color?)

@Composable
private fun colorsOf(variant: KbButtonVariant, enabled: Boolean): ButtonColors {
    val c = KbTheme.colors
    if (!enabled) {
        return ButtonColors(
            if (variant == KbButtonVariant.Quiet) Color.Transparent else c.surfaceSunken,
            c.textMuted.copy(alpha = 0.75f),
            if (variant == KbButtonVariant.Outline) c.border else null,
        )
    }
    return when (variant) {
        KbButtonVariant.Primary -> ButtonColors(c.primary, c.onPrimary, null)
        KbButtonVariant.Tonal -> ButtonColors(c.primaryContainer, c.onPrimaryContainer, null)
        KbButtonVariant.Outline -> ButtonColors(Color.Transparent, c.primary, c.borderStrong)
        KbButtonVariant.Quiet -> ButtonColors(Color.Transparent, c.primary, null)
        KbButtonVariant.Danger -> ButtonColors(c.danger, c.onDanger, null)
    }
}

/**
 * The app's button: radius md (never a pill), 48 dp minimum height (56 dp for [KbButtonSize.Lg]), a label that wraps
 * instead of clipping at 200 % text, press scale, ripple and the focus ring. Disabled is a sunken fill with muted
 * text, not just opacity.
 */
@Composable
fun KbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: KbButtonVariant = KbButtonVariant.Primary,
    size: KbButtonSize = KbButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = colorsOf(variant, enabled)
    val bg by animateColorAsState(colors.bg, kbTween(KbMotion.FAST), label = "btn-bg")
    val interaction = remember { MutableInteractionSource() }
    val shape = KbTheme.shapes.md
    val lg = size == KbButtonSize.Lg
    Box(
        modifier
            .kbPressScale(interaction)
            .kbFocusRing(interaction, KbRadius.md)
            .defaultMinSize(minWidth = 64.dp, minHeight = if (lg) 56.dp else KbTheme.size.control)
            .clip(shape)
            .background(bg)
            .then(if (colors.border != null) Modifier.border(1.dp, colors.border, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = colors.fg),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = if (lg) 24.dp else 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.fg, strokeWidth = 2.dp)
            } else if (leading != null) {
                Icon(leading, contentDescription = null, tint = colors.fg, modifier = Modifier.size(20.dp))
            }
            Text(
                text,
                style = if (lg) KbTheme.type.titleSm else KbTheme.type.label.copy(fontSize = KbTheme.type.body.fontSize),
                color = colors.fg,
                textAlign = TextAlign.Center,
            )
            trailing?.invoke()
        }
    }
}

/** Icon button emphasis. */
enum class KbIconVariant { Quiet, Tonal, Outline }

/**
 * A 48 dp icon button with an accessible [label] (also announced). [selected] makes it a toggle (pressed state is
 * announced and drawn tonal).
 */
@Composable
fun KbIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: KbIconVariant = KbIconVariant.Quiet,
    enabled: Boolean = true,
    selected: Boolean? = null,
    tint: Color? = null,
) {
    KbIconButtonBox(label, onClick, modifier, variant, enabled, selected) { fg ->
        Icon(icon, contentDescription = null, tint = tint ?: fg, modifier = Modifier.size(24.dp))
    }
}

/** A 48 dp icon button whose "icon" is a glyph (for example ふ for furigana). */
@Composable
fun KbGlyphButton(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    glyphStyle: TextStyle? = null,
) {
    KbIconButtonBox(label, onClick, modifier, KbIconVariant.Quiet, true, selected) { fg ->
        Text(glyph, style = (glyphStyle ?: KbTheme.type.titleSm).copy(color = fg), maxLines = 1)
    }
}

@Composable
private fun KbIconButtonBox(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    variant: KbIconVariant,
    enabled: Boolean,
    selected: Boolean?,
    content: @Composable (Color) -> Unit,
) {
    val c = KbTheme.colors
    val on = selected == true
    val bg = when {
        on -> c.primaryContainer
        variant == KbIconVariant.Tonal -> c.primaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> c.textMuted.copy(alpha = 0.5f)
        on || variant == KbIconVariant.Tonal -> c.onPrimaryContainer
        else -> c.textMuted
    }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(KbRadius.md)
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .kbPressScale(interaction)
            .kbFocusRing(interaction, KbRadius.md)
            .clip(shape)
            .background(bg)
            .then(if (variant == KbIconVariant.Outline) Modifier.border(1.dp, c.borderStrong, shape) else Modifier)
            .then(
                if (selected != null) {
                    Modifier.toggleable(on, interaction, ripple(), enabled = enabled, role = Role.Switch) { onClick() }
                } else {
                    Modifier.clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content(fg) }
}
