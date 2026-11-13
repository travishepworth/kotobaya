package kotobaya.android.review.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.review.ReviewAction
import kotobaya.android.review.ReviewActionsUiState
import kotobaya.android.review.ReviewIntents

/**
 * The overflow menu of the review screen: edit, flag, bury, suspend, never-forget, blacklist. After every action the
 * session rebuilds its queue (or re-renders the card for an edit), and a one-line 'Undo' offer shows the last result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewActionsBar(state: ReviewActionsUiState, cardId: String, intents: ReviewIntents) {
    var menu by remember { mutableStateOf(false) }
    var flagMenu by remember { mutableStateOf(false) }

    fun perform(action: ReviewAction) {
        menu = false
        flagMenu = false
        intents.perform(cardId, action)
    }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            state.message?.let { Text(T(it), Modifier.weight(1f)) }
            if (state.undoable) {
                TextButton(onClick = { intents.undoAction(cardId) }) { Text(T(R.string.review_action_undo)) }
            }
            TextButton(onClick = { intents.openActions(cardId); menu = true }) { Text(T(R.string.review_actions_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val s = state.card
                DropdownMenuItem(
                    text = { Text(T(R.string.review_action_edit)) },
                    onClick = {
                        menu = false
                        intents.edit(cardId)
                    },
                )
                DropdownMenuItem(text = { Text(T(R.string.review_action_flag)) }, onClick = { menu = false; flagMenu = true })
                DropdownMenuItem(
                    text = { Text(T(if (state.buried) R.string.review_action_unbury else R.string.review_action_bury)) },
                    onClick = { perform(ReviewAction.BURY) },
                )
                DropdownMenuItem(
                    text = { Text(T(if (s?.suspended == true) R.string.review_action_unsuspend else R.string.review_action_suspend)) },
                    onClick = { perform(ReviewAction.SUSPEND) },
                )
                if (s?.entrySeq != null) {
                    DropdownMenuItem(
                        text = { Text(T(if (s.wordOverride == "never_forget") R.string.review_action_clear_never_forget else R.string.review_action_never_forget)) },
                        onClick = { perform(ReviewAction.NEVER_FORGET) },
                    )
                    DropdownMenuItem(
                        text = { Text(T(if (s.wordOverride == "blacklisted") R.string.review_action_clear_blacklist else R.string.review_action_blacklist)) },
                        onClick = { perform(ReviewAction.BLACKLIST) },
                    )
                }
            }
            DropdownMenu(expanded = flagMenu, onDismissRequest = { flagMenu = false }) {
                for (c in 0..7) {
                    DropdownMenuItem(
                        text = { Text(if (c == 0) T(R.string.review_flag_none) else T(R.string.review_flag_colour, c)) },
                        onClick = { menu = false; flagMenu = false; intents.flag(cardId, c) },
                    )
                }
            }
        }
        if (state.emptied > 0) Text(T(R.string.review_edit_empty_front, state.emptied), Modifier.padding(horizontal = 8.dp))
    }

    state.editing?.let { note ->
        val values = remember(note) { mutableStateMapOf<String, String>().apply { putAll(note.values) } }
        ModalBottomSheet(onDismissRequest = intents::dismissEdit) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(T(R.string.review_edit_title))
                for (k in note.keys) {
                    OutlinedTextField(
                        value = values[k] ?: "",
                        onValueChange = { values[k] = it },
                        label = { Text(k) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = intents::dismissEdit) { Text(T(R.string.review_edit_cancel)) }
                    TextButton(onClick = { intents.saveEdit(cardId, values.toMap()) }) { Text(T(R.string.review_edit_save)) }
                }
            }
        }
    }
}
