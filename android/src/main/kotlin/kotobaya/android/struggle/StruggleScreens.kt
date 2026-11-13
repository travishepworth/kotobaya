package kotobaya.android.struggle

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.media.browse.Loaded
import org.koin.compose.koinInject

private sealed interface Page {
    data object Home : Page
    data class Word(val word: HotWordView) : Page
    data object Leeches : Page
    data object Confusables : Page
    data class Drill(val source: String) : Page
}

@Composable
fun StruggleScreen() {
    val source = koinInject<StruggleSource>()
    var page by remember { mutableStateOf<Page>(Page.Home) }
    val pending by StruggleDeepLink.pending.collectAsState()
    LaunchedEffect(pending) {
        val seq = pending ?: return@LaunchedEffect
        StruggleDeepLink.consume()
        val hot = (source.hotWords() as? Loaded.Data)?.value?.firstOrNull { it.entrySeq == seq }
        if (hot != null) page = Page.Word(hot)
    }
    when (val p = page) {
        Page.Home -> HomePage(source, onOpen = { page = it })
        is Page.Word -> WordPage(source, p.word) { page = Page.Home }
        Page.Leeches -> LeechesPage(source, onDrill = { page = Page.Drill("leeches") }) { page = Page.Home }
        Page.Confusables -> ConfusablesPage(source, onDrill = { page = Page.Drill("confusables") }) { page = Page.Home }
        is Page.Drill -> DrillPage(source, p.source) { page = Page.Home }
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
private fun HomePage(source: StruggleSource, onOpen: (Page) -> Unit) {
    var words by remember { mutableStateOf<Loaded<List<HotWordView>>?>(null) }
    var kanji by remember { mutableStateOf<Loaded<List<HotKanjiView>>?>(null) }
    LaunchedEffect(Unit) {
        words = source.hotWords()
        kanji = source.hotKanji()
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(T(R.string.struggle_title), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpen(Page.Leeches) }) { Text(T(R.string.struggle_leeches)) }
                OutlinedButton(onClick = { onOpen(Page.Confusables) }) { Text(T(R.string.struggle_confusables)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(Page.Drill("hot_words")) }) { Text(T(R.string.struggle_drill_hot)) }
            }
            Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
            Text(T(R.string.struggle_hot_words), style = MaterialTheme.typography.titleMedium)
        }
        val w = words
        if (w is Loaded.Data) {
            if (w.value.isEmpty()) item { Text(T(R.string.struggle_hot_words_none)) }
            items(w.value, key = { it.noteId }) { hw ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(Page.Word(hw)) }.padding(vertical = 4.dp)) {
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
        val k = kanji
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
private fun WordPage(source: StruggleSource, word: HotWordView, onBack: () -> Unit) {
    var sentences by remember { mutableStateOf<Loaded<List<SentenceItem>>?>(null) }
    var partners by remember { mutableStateOf<Loaded<List<ConfusableView>>?>(null) }
    var mnemonic by remember { mutableStateOf(word.mnemonic) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(word.noteId) {
        val seq = word.entrySeq ?: return@LaunchedEffect
        sentences = source.sentences(seq)
        partners = source.wordConfusables(seq)
    }
    fun report(r: Result<Unit>, ok: Int) {
        message = if (r.isSuccess) ok else R.string.struggle_write_failed
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            BackButton(onBack)
            Text(word.headword, style = MaterialTheme.typography.headlineMedium)
            Text("${word.reading} ${word.glosses.joinToString("; ")}".trim())
            Text(
                T(R.string.struggle_score, "%.1f".format(word.score)) + " · " + T(R.string.struggle_failures, word.failures14d),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(T(R.string.struggle_extra_drill_note), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(T(it)) }
            Text(T(R.string.struggle_mnemonic), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(mnemonic, { mnemonic = it }, Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { report(source.saveMnemonic(word.noteId, mnemonic), R.string.struggle_mnemonic_saved) } }) {
                Text(T(R.string.struggle_mnemonic_save))
            }
            Text(T(R.string.struggle_sentences), style = MaterialTheme.typography.titleMedium)
        }
        val s = sentences
        if (s is Loaded.Data) {
            if (s.value.isEmpty()) item { Text(T(R.string.struggle_sentences_none)) }
            items(s.value, key = { it.id }) { sent ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(sent.text)
                    OutlinedButton(onClick = { scope.launch { report(source.makeSentenceCard(sent), R.string.struggle_card_created) } }) {
                        Text(T(R.string.struggle_make_card))
                    }
                }
            }
        } else {
            item { LoadedBlock(s) {} }
        }
        item { Text(T(R.string.struggle_partners), style = MaterialTheme.typography.titleMedium) }
        val p = partners
        if (p is Loaded.Data) {
            if (p.value.isEmpty()) item { Text(T(R.string.struggle_partners_none)) }
            items(p.value, key = { "${it.noteAId}/${it.noteBId}/${it.reason}" }) { c ->
                Text("${c.labelA} / ${c.labelB}  (${reasonLabel(c.reason)}, ${statusLabel(c.status)})")
            }
        }
        item {
            Button(onClick = onBack) { Text(T(R.string.struggle_back)) }
        }
    }
}

@Composable
private fun LeechesPage(source: StruggleSource, onDrill: () -> Unit, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<LeechView>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<Loaded<*>?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(more: Boolean) {
        val r = source.leeches(if (more) cursor else null)
        state = r
        if (r is Loaded.Data) {
            val merged = if (more) rows + r.value.first else r.value.first
            rows = merged.associateBy { it.cardId }.values.toList()
            cursor = r.value.second
        }
    }
    LaunchedEffect(Unit) { load(false) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            BackButton(onBack)
            Text(T(R.string.struggle_leeches), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onDrill) { Text(T(R.string.struggle_drill_leeches)) }
            Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
            if (state !is Loaded.Data) LoadedBlock(state) {}
            if (state is Loaded.Data && rows.isEmpty()) Text(T(R.string.struggle_leeches_none))
        }
        items(rows, key = { it.cardId }) { l ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(l.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    T(R.string.struggle_lapses, l.lapses) + if (l.suspended) " · " + T(R.string.struggle_suspended) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (cursor != null) {
            item { OutlinedButton(onClick = { scope.launch { load(true) } }) { Text(T(R.string.struggle_more)) } }
        }
    }
}

@Composable
private fun ConfusablesPage(source: StruggleSource, onDrill: () -> Unit, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Loaded<List<ConfusableView>>?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { state = source.confusables() }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            BackButton(onBack)
            Text(T(R.string.struggle_confusables), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onDrill) { Text(T(R.string.struggle_drill_confusables)) }
            Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(T(it)) }
        }
        val s = state
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
                                onClick = {
                                    scope.launch {
                                        val r = source.setConfusable(c, st)
                                        message = if (r.isSuccess) R.string.struggle_pair_updated else R.string.struggle_write_failed
                                        if (r.isSuccess) state = source.confusables()
                                    }
                                },
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
private fun DrillPage(source: StruggleSource, kind: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Loaded<List<DrillItem>>?>(null) }
    var index by remember { mutableStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(kind) { state = source.drill(kind) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackButton(onBack)
        Text(T(R.string.struggle_drill_title), style = MaterialTheme.typography.titleLarge)
        Text(T(R.string.struggle_drill_note), style = MaterialTheme.typography.bodySmall)
        val s = state
        if (s !is Loaded.Data) {
            LoadedBlock(s) {}
            return@Column
        }
        val item = s.value.getOrNull(index)
        if (item == null) {
            Text(T(R.string.struggle_drill_done))
            return@Column
        }
        Text(T(R.string.struggle_drill_progress, index + 1, s.value.size))
        Text(item.front, style = MaterialTheme.typography.headlineLarge)
        if (failed) Text(T(R.string.struggle_write_failed))
        if (!revealed) {
            Button(onClick = { revealed = true }) { Text(T(R.string.struggle_drill_show)) }
        } else {
            Text(item.back)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to R.string.struggle_again, 3 to R.string.struggle_good).forEach { (rating, label) ->
                    Button(onClick = {
                        scope.launch {
                            failed = source.answerDrill(item.cardId, rating).isFailure
                            revealed = false
                            index += 1
                        }
                    }) { Text(T(label)) }
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
