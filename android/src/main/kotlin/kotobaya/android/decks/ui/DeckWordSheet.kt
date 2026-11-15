package kotobaya.android.decks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.decks.DeckWord
import kotobaya.android.decks.DecksIntents
import kotobaya.android.i18n.T
import kotobaya.android.nav.DictJump
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbSheet

/**
 * The action sheet of one deck word: status actions (only for dictionary words; `n:` custom notes have no word
 * status), add to my notes (when no note holds it), dictionary, remove (user decks only).
 */
@Composable
fun DeckWordSheet(word: DeckWord, userDeck: Boolean, intents: DecksIntents) {
    val seq = word.entrySeq
    KbSheet(onDismiss = intents::closeActions, title = word.label.ifEmpty { T(R.string.decks_word_unavailable) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (seq != null) {
                SheetAction(R.string.decks_act_known) { intents.setStatus(word, "known") }
                SheetAction(R.string.decks_act_never_forget) { intents.setStatus(word, "never_forget") }
                SheetAction(R.string.decks_act_ignore) { intents.setStatus(word, "ignored") }
                SheetAction(R.string.decks_act_blacklist) { intents.setStatus(word, "blacklisted") }
                SheetAction(R.string.decks_act_reset) { intents.setStatus(word, null) }
                if (!word.hasNote) SheetAction(R.string.decks_act_add_note) { intents.addToNotes(word) }
                SheetAction(R.string.dict_open) {
                    intents.closeActions()
                    DictJump.openWord(seq)
                }
                if (userDeck) SheetAction(R.string.decks_act_remove) { intents.removeFromDeck(word) }
            }
        }
    }
}

@Composable
private fun SheetAction(label: Int, onClick: () -> Unit) {
    KbButton(T(label), onClick, Modifier.fillMaxWidth(), variant = KbButtonVariant.Outline)
}
