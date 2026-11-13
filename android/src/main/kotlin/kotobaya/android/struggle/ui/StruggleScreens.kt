package kotobaya.android.struggle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.grammar.ui.CaptureEntrySlot
import kotobaya.android.i18n.T
import kotobaya.android.media.browse.Loaded
import kotobaya.android.struggle.HotWordView
import kotobaya.android.struggle.StruggleConfusablesState
import kotobaya.android.struggle.StruggleDrillState
import kotobaya.android.struggle.StruggleHomeState
import kotobaya.android.struggle.StruggleIntents
import kotobaya.android.struggle.StruggleLeechesState
import kotobaya.android.struggle.StrugglePage
import kotobaya.android.struggle.StruggleUiState
import kotobaya.android.struggle.StruggleWordState

/** The struggle screen: the page in [state]; [captureEntry] is each sentence's grammar capture. */
@Composable
fun StruggleScreen(state: StruggleUiState, intents: StruggleIntents, captureEntry: CaptureEntrySlot) {
    when (val p = state.page) {
        StrugglePage.Home -> HomePage(state.home, intents)
        is StrugglePage.Word -> WordPage(p.word, state.word, intents, captureEntry)
        StrugglePage.Leeches -> LeechesPage(state.leeches, intents)
        StrugglePage.Confusables -> ConfusablesPage(state.confusables, intents)
        is StrugglePage.Drill -> DrillPage(state.drill, intents)
    }
}

/** Shows loading / needs-connection / error, else [content] with the data. */
@Composable
private fun <T> LoadedBlock(state: Loaded<T>?, content: @Composable (T) -> Unit) {
    when (state) {
        null -> Text(T(R.string.struggle_loading))
        is Loaded.NeedsConnection -> Text(T(R.string.struggle_needs_connection))
        is Loaded.Failed -> Text(T(R.string.struggle_error))
        is Loaded.Data -> {
            if (state.fromCache) Text(T(R.string.struggle_cached), style = MaterialTheme.typography.bodySmall)
            content(state.value)
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    OutlinedButton(onClick = onBack) { Text(T(R.string.struggle_back)) }
}

@Composable
private fun HomePage(state: StruggleHomeState, intents: StruggleIntents) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(T(R.string.struggle_title), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { intents.open(StrugglePage.Leeches) }) { Text(T(R.string.struggle_leeches)) }
                OutlinedButton(onClick = { intents.open(StrugglePage.Confusables) }) { Text(T(R.string.struggle_confusables)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { intents.open(StrugglePage.Drill("hot_words")) }) { Text(T(R.string.struggle_drill_hot)) }
            }
            Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
            Text(T(R.string.struggle_hot_words), style = MaterialTheme.typography.titleMedium)
        }
        val w = state.words
        if (w is Loaded.Data) {
            if (w.value.isEmpty()) item { Text(T(R.string.struggle_hot_words_none)) }
            items(w.value, key = { it.noteId }) { hw ->
                Column(Modifier.fillMaxWidth().clickable { intents.open(StrugglePage.Word(hw)) }.padding(vertical = 4.dp)) {
                    Text(hw.headword, style = MaterialTheme.typography.titleMedium)
                    Text("${hw.reading} ${hw.glosses.take(3).joinToString("; ")}".trim())
                    Text(
                        T(R.string.struggle_score, "%.1f".format(hw.score)) + " · " + T(R.string.struggle_failures, hw.failures14d),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            item { LoadedBlock(w) {} }
        }
        item { Text(T(R.string.struggle_hot_kanji), style = MaterialTheme.typography.titleMedium) }
        val k = state.kanji
        if (k is Loaded.Data) {
            if (k.value.isEmpty()) item { Text(T(R.string.struggle_hot_kanji_none)) }
            items(k.value, key = { it.literal }) { hk ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${hk.literal}  ${hk.meanings.take(3).joinToString(", ")}")
                    Text(
                        T(R.string.struggle_score, "%.1f".format(hk.score)) + " · " + T(R.string.struggle_kanji_words, hk.wordCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            item { LoadedBlock(k) {} }
        }
    }
}

@Composable
private fun WordPage(word: HotWordView, state: StruggleWordState, intents: StruggleIntents, captureEntry: CaptureEntrySlot) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            BackButton(intents::home)
            Text(word.headword, style = MaterialTheme.typography.headlineMedium)
            Text("${word.reading} ${word.glosses.joinToString("; ")}".trim())
            Text(
                T(R.string.struggle_score, "%.1f".format(word.score)) + " · " + T(R.string.struggle_failures, word.failures14d),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(T(R.string.struggle_extra_drill_note), style = MaterialTheme.typography.bodySmall)
            state.message?.let { Text(T(it)) }
            Text(T(R.string.struggle_mnemonic), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.mnemonic, intents::setMnemonic, Modifier.fillMaxWidth())
            Button(onClick = intents::saveMnemonic) {
                Text(T(R.string.struggle_mnemonic_save))
            }
            Text(T(R.string.struggle_sentences), style = MaterialTheme.typography.titleMedium)
        }
        val s = state.sentences
        if (s is Loaded.Data) {
            if (s.value.isEmpty()) item { Text(T(R.string.struggle_sentences_none)) }
            items(s.value, key = { it.id }) { sent ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(sent.text)
                    captureEntry(sent.id, sent.mediaId, sent.text)
                    OutlinedButton(onClick = { intents.makeSentenceCard(sent) }) {
                        Text(T(R.string.struggle_make_card))
                    }
                }
            }
        } else {
            item { LoadedBlock(s) {} }
        }
        item { Text(T(R.string.struggle_partners), style = MaterialTheme.typography.titleMedium) }
        val p = state.partners
        if (p is Loaded.Data) {
            if (p.value.isEmpty()) item { Text(T(R.string.struggle_partners_none)) }
            items(p.value, key = { "${it.noteAId}/${it.noteBId}/${it.reason}" }) { c ->
                Text("${c.labelA} / ${c.labelB}  (${reasonLabel(c.reason)}, ${statusLabel(c.status)})")
            }
        }
        item {
            Button(onClick = intents::home) { Text(T(R.string.struggle_back)) }
        }
    }
}

@Composable
private fun LeechesPage(state: StruggleLeechesState, intents: StruggleIntents) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            BackButton(intents::home)
            Text(T(R.string.struggle_leeches), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { intents.open(StrugglePage.Drill("leeches")) }) { Text(T(R.string.struggle_drill_leeches)) }
            Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
            if (state.load !is Loaded.Data) LoadedBlock(state.load) {}
            if (state.load is Loaded.Data && state.rows.isEmpty()) Text(T(R.string.struggle_leeches_none))
        }
        items(state.rows, key = { it.cardId }) { l ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(l.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    T(R.string.struggle_lapses, l.lapses) + if (l.suspended) " · " + T(R.string.struggle_suspended) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.cursor != null) {
            item { OutlinedButton(onClick = intents::moreLeeches) { Text(T(R.string.struggle_more)) } }
        }
    }
}

@Composable
private fun ConfusablesPage(state: StruggleConfusablesState, intents: StruggleIntents) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            BackButton(intents::home)
            Text(T(R.string.struggle_confusables), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { intents.open(StrugglePage.Drill("confusables")) }) { Text(T(R.string.struggle_drill_confusables)) }
            Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
            state.message?.let { Text(T(it)) }
        }
        val s = state.pairs
        if (s is Loaded.Data) {
            if (s.value.isEmpty()) item { Text(T(R.string.struggle_confusables_none)) }
            items(s.value, key = { "${it.noteAId}/${it.noteBId}/${it.reason}" }) { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${c.labelA} / ${c.labelB}", style = MaterialTheme.typography.titleMedium)
                    Text("${reasonLabel(c.reason)} · ${statusLabel(c.status)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("confirmed" to R.string.struggle_confirm, "rejected" to R.string.struggle_reject).forEach { (st, label) ->
                            OutlinedButton(
                                enabled = c.status != st,
                                onClick = { intents.setConfusable(c, st) },
                            ) { Text(T(label)) }
                        }
                    }
                }
            }
        } else {
            item { LoadedBlock(s) {} }
        }
    }
}

/** Flip-through drill; every answer is logged with `mode = drill`, so scheduling is untouched. */
@Composable
private fun DrillPage(state: StruggleDrillState, intents: StruggleIntents) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackButton(intents::home)
        Text(T(R.string.struggle_drill_title), style = MaterialTheme.typography.titleLarge)
        Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
        val s = state.items
        if (s !is Loaded.Data) {
            LoadedBlock(s) {}
            return@Column
        }
        val item = s.value.getOrNull(state.index)
        if (item == null) {
            Text(T(R.string.struggle_drill_done))
            return@Column
        }
        Text(T(R.string.struggle_drill_progress, state.index + 1, s.value.size))
        Text(item.front, style = MaterialTheme.typography.headlineLarge)
        if (state.failed) Text(T(R.string.struggle_write_failed))
        if (!state.revealed) {
            Button(onClick = intents::reveal) { Text(T(R.string.struggle_drill_show)) }
        } else {
            Text(item.back)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to R.string.struggle_again, 3 to R.string.struggle_good).forEach { (rating, label) ->
                    Button(onClick = { intents.answerDrill(rating) }) { Text(T(label)) }
                }
            }
        }
    }
}

@Composable
private fun reasonLabel(reason: String): String = when (reason) {
    "homophone" -> T(R.string.struggle_reason_homophone)
    "look_alike" -> T(R.string.struggle_reason_look_alike)
    "meaning" -> T(R.string.struggle_reason_meaning)
    else -> T(R.string.struggle_reason_other)
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "suggested" -> T(R.string.struggle_status_suggested)
    "confirmed" -> T(R.string.struggle_status_confirmed)
    "rejected" -> T(R.string.struggle_status_rejected)
    else -> T(R.string.struggle_status_other)
}
