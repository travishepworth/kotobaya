package kotobaya.android.review.actions

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.review.ReviewBackend
import kotobaya.android.review.ReviewSession
import kotobaya.sync.client.apply.LearningTables

/** Builds the toolbar actions over a backend's store and minter. */
fun <L : LearningTables> ReviewBackend<L>.newActions(): ReviewActions<L> =
    ReviewActions(store, minter, clock, nextStudyDayStart)

/**
 * The overflow menu of the review screen: edit, flag, bury, suspend, never-forget, blacklist. After every action the
 * session rebuilds its queue (or re-renders the card for an edit), and a one-line 'Undo' offer shows the last result.
 */
@Composable
fun ReviewActionsBar(backend: ReviewBackend<*>, session: ReviewSession<*>, cardId: String) {
    ActionsBarFor(backend, session, cardId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <L : LearningTables> ActionsBarFor(backend: ReviewBackend<L>, session: ReviewSession<*>, cardId: String) {
    val actions = remember(backend) { backend.newActions() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var flagMenu by remember { mutableStateOf(false) }
    var editing by remember(cardId) { mutableStateOf<EditableNote?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var undoable by remember { mutableStateOf(false) }
    var emptied by remember { mutableStateOf(0) }
    var state by remember(cardId) { mutableStateOf<CardActionState?>(null) }

    fun refreshState() = scope.launch(Dispatchers.IO) { state = actions.stateOf(cardId) }

    fun perform(requeue: Boolean, okMessage: Int, block: () -> ActionResult) {
        menu = false
        flagMenu = false
        scope.launch(Dispatchers.IO) {
            val r = block()
            when (r) {
                is ActionResult.Done -> {
                    if (requeue) session.start() else session.refreshCurrent()
                    message = okMessage
                    undoable = r.undoable
                    emptied = r.warnings.size
                }
                is ActionResult.Refused -> { message = R.string.review_action_refused; undoable = false; emptied = 0 }
                ActionResult.NotApplicable -> { message = R.string.review_action_not_applicable; undoable = false; emptied = 0 }
            }
            state = actions.stateOf(cardId)
        }
    }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            message?.let { Text(T(it), Modifier.weight(1f)) }
            if (undoable) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        if (actions.undo()) session.start()
                        undoable = false
                        message = null
                        state = actions.stateOf(cardId)
                    }
                }) { Text(T(R.string.review_action_undo)) }
            }
            TextButton(onClick = { refreshState(); menu = true }) { Text(T(R.string.review_actions_menu)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val s = state
                DropdownMenuItem(
                    text = { Text(T(R.string.review_action_edit)) },
                    onClick = {
                        menu = false
                        scope.launch(Dispatchers.IO) { editing = actions.editable(cardId) }
                    },
                )
                DropdownMenuItem(text = { Text(T(R.string.review_action_flag)) }, onClick = { menu = false; flagMenu = true })
                DropdownMenuItem(
                    text = { Text(T(if (s?.buriedUntil != null && s.buriedUntil > backend.clock()) R.string.review_action_unbury else R.string.review_action_bury)) },
                    onClick = { perform(true, R.string.review_action_done) { actions.bury(cardId) } },
                )
                DropdownMenuItem(
                    text = { Text(T(if (s?.suspended == true) R.string.review_action_unsuspend else R.string.review_action_suspend)) },
                    onClick = { perform(true, R.string.review_action_done) { actions.suspend(cardId) } },
                )
                if (s?.entrySeq != null) {
                    DropdownMenuItem(
                        text = { Text(T(if (s.wordOverride == "never_forget") R.string.review_action_clear_never_forget else R.string.review_action_never_forget)) },
                        onClick = { perform(true, R.string.review_action_done) { actions.neverForget(cardId) } },
                    )
                    DropdownMenuItem(
                        text = { Text(T(if (s.wordOverride == "blacklisted") R.string.review_action_clear_blacklist else R.string.review_action_blacklist)) },
                        onClick = { perform(true, R.string.review_action_done) { actions.blacklist(cardId) } },
                    )
                }
            }
            DropdownMenu(expanded = flagMenu, onDismissRequest = { flagMenu = false }) {
                for (c in 0..7) {
                    DropdownMenuItem(
                        text = { Text(if (c == 0) T(R.string.review_flag_none) else T(R.string.review_flag_colour, c)) },
                        onClick = { perform(false, R.string.review_action_done) { actions.flag(cardId, c) } },
                    )
                }
            }
        }
        if (emptied > 0) Text(T(R.string.review_edit_empty_front, emptied), Modifier.padding(horizontal = 8.dp))
    }

    editing?.let { note ->
        val values = remember(note) { mutableStateMapOf<String, String>().apply { putAll(note.values) } }
        ModalBottomSheet(onDismissRequest = { editing = null }) {
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
                    TextButton(onClick = { editing = null }) { Text(T(R.string.review_edit_cancel)) }
                    TextButton(onClick = {
                        val edited = values.toMap()
                        editing = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { actions.saveEdit(cardId, edited) }
                            when (r) {
                                is ActionResult.Done -> {
                                    withContext(Dispatchers.IO) { session.refreshCurrent() }
                                    message = R.string.review_edit_saved
                                    undoable = r.undoable
                                    emptied = r.warnings.size
                                }
                                else -> { message = R.string.review_action_refused; undoable = false }
                            }
                        }
                    }) { Text(T(R.string.review_edit_save)) }
                }
            }
        }
    }
}
