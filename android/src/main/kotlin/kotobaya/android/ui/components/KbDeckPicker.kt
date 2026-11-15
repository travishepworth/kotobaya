package kotobaya.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.decks.DeckChoice
import kotobaya.android.i18n.T
import kotobaya.android.ui.theme.KbTheme

/**
 * The "Add to deck" sheet (AND-ADD2DECK): the user decks in draw order. With [onNone] null a row toggles membership and
 * shows a check mark on decks that hold the item; with [onNone] set (adding a note) a row picks the deck and the first
 * row is "No deck". [onCreate] adds a user deck by name.
 */
@Composable
fun KbDeckPicker(
    title: String,
    decks: List<DeckChoice>,
    onPick: (DeckChoice) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    onNone: (() -> Unit)? = null,
) {
    val c = KbTheme.colors
    var name by remember { mutableStateOf("") }
    KbSheet(onDismiss, title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (onNone != null) KbListRow(title = T(R.string.deck_picker_none), supporting = T(R.string.deck_picker_none_hint), onClick = onNone)
            if (decks.isEmpty()) Text(T(R.string.deck_picker_empty), style = KbTheme.type.body, color = c.textMuted)
            decks.forEachIndexed { i, d ->
                if (i > 0 || onNone != null) KbDivider()
                KbListRow(
                    title = d.name,
                    onClick = { onPick(d) },
                    chevron = false,
                    trailing = if (onNone == null && d.member) {
                        { Icon(KbIcons.Check, T(R.string.deck_picker_in_deck), tint = c.primary) }
                    } else {
                        null
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            KbTextField(name, { name = it }, label = T(R.string.deck_picker_new_label), modifier = Modifier.weight(1f))
            KbButton(T(R.string.deck_picker_create), { onCreate(name); name = "" }, variant = KbButtonVariant.Tonal, enabled = name.isNotBlank())
        }
    }
}
