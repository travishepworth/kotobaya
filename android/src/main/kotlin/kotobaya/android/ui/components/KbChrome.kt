package kotobaya.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme

/**
 * The top bar of a pushed page (64 dp): back button, [title] (title role, wraps to 2 lines), trailing [actions].
 * Top-level pages use a big [KbPageTitle] in their content instead.
 */
@Composable
fun KbTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = KbTheme.size.topBar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) KbIconButton(KbIcons.Back, backLabel, onBack) else Box(Modifier.width(12.dp))
        Text(
            title,
            style = KbTheme.type.title,
            color = KbTheme.colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        actions()
    }
}

/** A settings row with a switch: the whole row toggles (48 dp+), [supporting] explains it. */
@Composable
fun KbSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val c = KbTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, interaction, ripple(), enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = KbTheme.type.body, color = if (enabled) c.text else c.textMuted)
            if (supporting != null) Text(supporting, style = KbTheme.type.bodySm, color = c.textMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onPrimary,
                checkedTrackColor = c.primary,
                uncheckedThumbColor = c.borderStrong,
                uncheckedTrackColor = c.surfaceSunken,
                uncheckedBorderColor = c.borderStrong,
            ),
        )
    }
}

/** One option of a radio group (the whole row selects). */
@Composable
fun KbRadioRow(text: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    val c = KbTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(KbRadius.sm))
            .selectable(selected, interaction, ripple(), role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.primary, unselectedColor = c.borderStrong))
        Column(Modifier.weight(1f)) {
            Text(text, style = KbTheme.type.body, color = c.text)
            if (supporting != null) Text(supporting, style = KbTheme.type.bodySm, color = c.textMuted)
        }
    }
}

/** A key / value row of a details list (label muted on top at large text, side by side otherwise). */
@Composable
fun KbFactRow(label: String, value: String, modifier: Modifier = Modifier, valueStyle: androidx.compose.ui.text.TextStyle = KbTheme.type.body) {
    val c = KbTheme.colors
    val stacked = fontScale() >= LARGE_TEXT
    if (stacked) {
        Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = KbTheme.type.bodySm, color = c.textMuted)
            Text(value, style = valueStyle, color = c.text)
        }
    } else {
        Row(
            modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(label, style = KbTheme.type.bodySm, color = c.textMuted, modifier = Modifier.weight(1f))
            Text(value, style = valueStyle, color = c.text, modifier = Modifier.weight(1.4f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/**
 * A bottom sheet (phones) with the xl top corners, grab handle and scrolling content; actions stack full width at
 * the bottom of [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KbSheet(onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = KbTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = KbTheme.shapes.sheet,
        containerColor = c.surfaceRaised,
        contentColor = c.text,
        scrimColor = c.scrim,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = KbTheme.type.title, color = c.text, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** A confirm dialog: title, body, actions right-aligned with the destructive one last (DESIGN §3 Dialog). */
@Composable
fun KbConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val c = KbTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = KbTheme.shapes.xl,
        containerColor = c.surfaceRaised,
        title = { Text(title, style = KbTheme.type.title, color = c.text) },
        text = { Text(body, style = KbTheme.type.body, color = c.textMuted) },
        dismissButton = { KbButton(dismiss, onDismiss, variant = KbButtonVariant.Quiet) },
        confirmButton = {
            KbButton(confirm, onConfirm, variant = if (destructive) KbButtonVariant.Danger else KbButtonVariant.Primary)
        },
    )
}

/** One item of a [KbMenu]. */
data class KbMenuItem(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val onClick: () -> Unit)

/** An overflow menu (raised surface, elevation 2, radius md) anchored to its caller. */
@Composable
fun KbMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<KbMenuItem>, offset: DpOffset = DpOffset(0.dp, 4.dp)) {
    val c = KbTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = KbTheme.shapes.md,
        containerColor = c.surfaceRaised,
        modifier = Modifier.background(c.surfaceRaised),
    ) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label, style = KbTheme.type.body) },
                onClick = { onDismiss(); item.onClick() },
                leadingIcon = item.icon?.let { i -> { Icon(i, null, modifier = Modifier.size(20.dp)) } },
                colors = MenuDefaults.itemColors(
                    textColor = if (item.danger) c.danger else c.text,
                    leadingIconColor = if (item.danger) c.danger else c.textMuted,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** A tappable row for in-panel navigation (Settings index, Struggle links) with icon tile. */
@Composable
fun KbNavRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    KbListRow(
        title = title,
        supporting = supporting,
        onClick = onClick,
        selected = selected,
        modifier = modifier,
        trailing = trailing,
        leading = { KbIconTile(icon) },
    )
}

/** A 36 dp tinted tile holding an icon (list leading element). */
@Composable
fun KbIconTile(icon: ImageVector, modifier: Modifier = Modifier, container: androidx.compose.ui.graphics.Color? = null, tint: androidx.compose.ui.graphics.Color? = null) {
    val c = KbTheme.colors
    Box(
        modifier.size(36.dp).clip(RoundedCornerShape(KbRadius.sm)).background(container ?: c.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint ?: c.textMuted, modifier = Modifier.size(20.dp)) }
}

/** Clickable without ripple bounds (for whole-card taps that already show their own state). */
fun Modifier.kbClickable(onClick: () -> Unit, role: Role = Role.Button): Modifier =
    this.clickable(role = role, onClick = onClick)
