package kotobaya.android.grammar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.net.ApiError
import kotobaya.android.pairing.TokenStore
import kotobaya.android.pairing.uuidV7
import kotobaya.android.settings.SettingsStore
import org.koin.compose.koinInject

/**
 * Collapsed "Saw something weird" entry for a sentence. Hook line for sentence views:
 * `GrammarCaptureEntry(sentenceId, mediaId, text)`.
 */
@Composable
fun GrammarCaptureEntry(sentenceId: String, mediaId: String?, text: String) {
    var open by remember(sentenceId) { mutableStateOf(false) }
    if (open) {
        GrammarCapture(sentenceId, mediaId, text) { open = false }
    } else {
        OutlinedButton(onClick = { open = true }) { Text(T(R.string.gc_open)) }
    }
}

@Composable
fun GrammarCapture(sentenceId: String, mediaId: String?, text: String, onClose: () -> Unit) {
    val settings = koinInject<SettingsStore>()
    val tokens = koinInject<TokenStore>()
    val ctx = LocalContext.current
    val repo = remember { GrammarCaptureRepository({ settings.serverBaseUrl }, { tokens.token }) }
    val scope = rememberCoroutineScope()

    var field by remember { mutableStateOf(TextFieldValue(text)) }
    var span by remember { mutableStateOf<IntRange?>(null) }
    var existing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var rule by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GrammarPointView>>(emptyList()) }
    var chosen by remember { mutableStateOf<GrammarPointView?>(null) }
    var test by remember { mutableStateOf<RuleTestView?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun fail(e: ApiError): String = when (e) {
        is ApiError.Validation ->
            ctx.getString(R.string.gc_rule_error, listOfNotNull(e.pointer, e.code).joinToString(" "))
        is ApiError.Offline, is ApiError.Unreachable -> ctx.getString(R.string.gc_needs_connection)
        else -> ctx.getString(R.string.gc_failed)
    }

    LaunchedEffect(q, existing) {
        val query = q.trim()
        if (!existing || query.isEmpty()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        results = runCatching { repo.search(query) }.getOrDefault(emptyList())
    }

    /** Creates the new point (with its draft rule) once, so the server tester has an id. */
    suspend fun ensurePoint(): String? {
        if (existing) {
            val c = chosen
            if (c == null) message = ctx.getString(R.string.gc_need_point)
            return c?.id
        }
        if (title.isBlank()) {
            message = ctx.getString(R.string.gc_need_title)
            return null
        }
        val id = pendingId ?: uuidV7().also { pendingId = it }
        if (!created) {
            repo.createPoint(id, title.trim(), pattern.trim(), rule)
            created = true
        }
        return id
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(T(R.string.gc_title), style = MaterialTheme.typography.titleMedium)
        Text(T(R.string.gc_select))
        OutlinedTextField(
            value = field,
            onValueChange = { v ->
                // Read-only: keep the text, accept only the selection.
                field = TextFieldValue(text, TextRange(v.selection.start, v.selection.end))
                val s = minOf(v.selection.start, v.selection.end).coerceIn(0, text.length)
                val e = maxOf(v.selection.start, v.selection.end).coerceIn(0, text.length)
                if (e > s) {
                    span = s until e
                    val surface = text.substring(s, e)
                    rule = surface
                    if (pendingId == null) {
                        title = surface
                        pattern = surface
                    }
                    test = null
                    message = null
                }
            },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val sp = span
        Text(if (sp == null) T(R.string.gc_no_span) else T(R.string.gc_span, text.substring(sp.first, sp.last + 1)))

        Row {
            RadioButton(selected = !existing, onClick = { existing = false })
            Text(T(R.string.gc_mode_new), Modifier.padding(top = 12.dp))
            RadioButton(selected = existing, onClick = { existing = true })
            Text(T(R.string.gc_mode_existing), Modifier.padding(top = 12.dp))
        }
        if (!existing) {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), enabled = pendingId == null, label = { Text(T(R.string.gc_field_title)) })
            OutlinedTextField(pattern, { pattern = it }, Modifier.fillMaxWidth(), enabled = pendingId == null, label = { Text(T(R.string.gc_field_pattern)) })
        } else {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text(T(R.string.gc_search)) })
            if (q.isNotBlank() && results.isEmpty()) Text(T(R.string.gc_no_results))
            results.forEach { p ->
                Row {
                    RadioButton(selected = chosen?.id == p.id, onClick = { chosen = p })
                    Text(p.title + if (p.pattern.isNotEmpty()) "  ${p.pattern}" else "", Modifier.padding(top = 12.dp))
                }
            }
        }

        OutlinedTextField(rule, { rule = it; test = null }, Modifier.fillMaxWidth(), label = { Text(T(R.string.gc_field_rule)) })
        OutlinedButton(
            enabled = !busy && rule.isNotBlank() && (!existing || chosen != null),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        val id = ensurePoint()
                        if (id != null) test = repo.testRule(id, rule.trim())
                    } catch (e: ApiError) {
                        message = fail(e)
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(T(if (busy) R.string.gc_testing else R.string.gc_test)) }
        test?.let { t ->
            Text(T(R.string.gc_test_result, t.matches.size, t.scanned))
            if (t.truncated) Text(T(R.string.gc_test_truncated))
            t.matches.forEach { m -> Text(m.text, style = MaterialTheme.typography.bodySmall) }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (done) Text(T(R.string.gc_saved))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && !done,
                onClick = {
                    val s = span
                    if (s == null) {
                        message = ctx.getString(R.string.gc_need_span)
                    } else {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                val id = ensurePoint()
                                if (id != null) {
                                    repo.putSpan(id, sentenceId, s.first, s.last + 1, mediaId)
                                    val c = chosen
                                    if (existing && c != null && rule.isNotBlank()) repo.appendRule(c, rule)
                                    done = true
                                }
                            } catch (e: ApiError) {
                                message = fail(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            ) { Text(T(if (busy) R.string.gc_saving else R.string.gc_save)) }
            OutlinedButton(onClick = onClose) { Text(T(if (done) R.string.gc_close else R.string.gc_cancel)) }
        }
    }
}
