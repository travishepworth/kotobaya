package kotobaya.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.kbTween

/**
 * Text input (DESIGN §3 Input): a visible [label] above, 48 dp field with a 1 dp `border-strong` edge that turns into
 * the indigo ring on focus, [hint] under it, and [error] as icon + text under the field (never only a red border).
 */
@Composable
fun KbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = KbTheme.type.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = KbTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = KbTheme.type.label, color = if (enabled) c.text else c.textMuted)
        FieldBox(error != null, enabled) { interaction ->
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                readOnly = readOnly,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = textStyle.copy(color = if (enabled) c.text else c.textMuted),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .semantics {
                        contentDescription = label
                        if (error != null) error(error)
                    },
            )
        }
        FieldNote(hint, error)
    }
}

/** [KbTextField] over a [TextFieldValue] (selection-aware, for the grammar capture). */
@Composable
fun KbTextFieldValue(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    textStyle: TextStyle = KbTheme.type.body,
) {
    val c = KbTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = KbTheme.type.label, color = c.text)
        FieldBox(false, true) { interaction ->
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                readOnly = readOnly,
                textStyle = textStyle.copy(color = c.text),
                cursorBrush = SolidColor(c.primary),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun FieldBox(isError: Boolean, enabled: Boolean, content: @Composable (MutableInteractionSource) -> Unit) {
    val c = KbTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge by animateColorAsState(
        when {
            isError -> c.danger
            focused -> c.focus
            enabled -> c.borderStrong
            else -> c.border
        },
        kbTween(KbMotion.FAST),
    )
    val shape = RoundedCornerShape(KbRadius.sm)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (enabled) c.surface else c.surfaceSunken)
            .border(if (focused || isError) 2.dp else 1.dp, edge, shape),
        contentAlignment = Alignment.CenterStart,
    ) { content(interaction) }
}

@Composable
private fun FieldNote(hint: String?, error: String?) {
    val c = KbTheme.colors
    if (error != null) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(KbIcons.Alert, null, tint = c.danger, modifier = Modifier.padding(top = 2.dp).size(16.dp))
            Text(error, style = KbTheme.type.bodySm, color = c.danger)
        }
    } else if (hint != null) {
        Text(hint, style = KbTheme.type.bodySm, color = c.textMuted)
    }
}

/**
 * The search field: search icon, hidden label (announced), placeholder, clear button. Enter runs [onSearch].
 */
@Composable
fun KbSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    clearLabel: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = label,
) {
    val c = KbTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(KbRadius.md)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(c.surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) c.focus else c.borderStrong, shape)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(KbIcons.Search, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (value.isEmpty()) Text(placeholder, style = KbTheme.type.body, color = c.textMuted, maxLines = 2)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = KbTheme.type.body.copy(color = c.text, localeList = kotobaya.android.ui.theme.JapaneseLocale),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        if (value.isNotEmpty()) {
            KbIconButton(KbIcons.Close, clearLabel, onClick = { onValueChange("") })
        }
    }
}
