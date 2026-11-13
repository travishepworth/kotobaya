package kotobaya.android.grammar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotobaya.android.R
import kotobaya.android.net.ApiError
import kotobaya.android.pairing.uuidV7
import kotobaya.android.vm.StateViewModel
import kotobaya.android.vm.UiText

/** The "Saw something weird" panel of one sentence. Closed until [GrammarCaptureIntents.open]. */
data class GrammarCaptureUiState(
    val open: Boolean = false,
    /** The selected span of the sentence, or null. */
    val span: IntRange? = null,
    /** Attach to an existing point (search) instead of creating one. */
    val existing: Boolean = false,
    val title: String = "",
    val pattern: String = "",
    val rule: String = "",
    val query: String = "",
    val results: List<GrammarPointView> = emptyList(),
    val chosen: GrammarPointView? = null,
    val test: RuleTestView? = null,
    /** An error or hint line. */
    val message: UiText? = null,
    val busy: Boolean = false,
    /** The new point has its id (title and pattern are frozen). */
    val frozen: Boolean = false,
    val done: Boolean = false,
)

/** What the capture panel can ask for (no-ops by default, for previews). */
interface GrammarCaptureIntents {
    fun open() {}
    fun close() {}
    /** The selection in the read-only sentence field changed. */
    fun select(start: Int, end: Int) {}
    fun setExisting(existing: Boolean) {}
    fun setTitle(title: String) {}
    fun setPattern(pattern: String) {}
    fun setRule(rule: String) {}
    fun setQuery(query: String) {}
    fun choose(point: GrammarPointView) {}
    fun testRule() {}
    fun save() {}
}

/** One sentence's capture panel: pick a span, attach it to a new or existing grammar point, test a rule. Online only. */
class GrammarCaptureViewModel(
    private val sentenceId: String,
    private val mediaId: String?,
    private val text: String,
    private val port: GrammarCapturePort,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = ::uuidV7,
) : StateViewModel<GrammarCaptureUiState>(GrammarCaptureUiState(), io), GrammarCaptureIntents {
    private var pendingId: String? = null
    private var created = false
    private var searchJob: Job? = null

    override fun open() {
        close()
        update { it.copy(open = true) }
    }

    /** Closing forgets everything (reopening starts over), as the panel always did. */
    override fun close() {
        searchJob?.cancel()
        pendingId = null
        created = false
        update { GrammarCaptureUiState() }
    }

    override fun select(start: Int, end: Int) {
        val s = minOf(start, end).coerceIn(0, text.length)
        val e = maxOf(start, end).coerceIn(0, text.length)
        if (e <= s) return
        val surface = text.substring(s, e)
        update {
            it.copy(
                span = s until e,
                rule = surface,
                title = if (pendingId == null) surface else it.title,
                pattern = if (pendingId == null) surface else it.pattern,
                test = null,
                message = null,
            )
        }
    }

    override fun setExisting(existing: Boolean) {
        update { it.copy(existing = existing) }
        searchPoints()
    }

    override fun setTitle(title: String) = update { it.copy(title = title) }

    override fun setPattern(pattern: String) = update { it.copy(pattern = pattern) }

    override fun setRule(rule: String) = update { it.copy(rule = rule, test = null) }

    override fun setQuery(query: String) {
        update { it.copy(query = query) }
        searchPoints()
    }

    override fun choose(point: GrammarPointView) = update { it.copy(chosen = point) }

    /** Debounced point search (existing mode). */
    private fun searchPoints() {
        searchJob?.cancel()
        val s = current
        val query = s.query.trim()
        if (!s.existing || query.isEmpty()) {
            update { it.copy(results = emptyList()) }
            return
        }
        searchJob = launchIo {
            delay(SEARCH_DEBOUNCE_MS)
            val results = runCatching { port.search(query) }.getOrDefault(emptyList())
            update { it.copy(results = results) }
        }
    }

    private fun fail(e: ApiError): UiText = when (e) {
        is ApiError.Validation -> UiText(R.string.gc_rule_error, listOfNotNull(e.pointer, e.code).joinToString(" "))
        is ApiError.Offline, is ApiError.Unreachable -> UiText(R.string.gc_needs_connection)
        else -> UiText(R.string.gc_failed)
    }

    /** Creates the new point (with its draft rule) once, so the server tester has an id. */
    private suspend fun ensurePoint(): String? {
        val s = current
        if (s.existing) {
            val c = s.chosen
            if (c == null) update { it.copy(message = UiText(R.string.gc_need_point)) }
            return c?.id
        }
        if (s.title.isBlank()) {
            update { it.copy(message = UiText(R.string.gc_need_title)) }
            return null
        }
        val id = pendingId ?: newId().also { pendingId = it; update { u -> u.copy(frozen = true) } }
        if (!created) {
            port.createPoint(id, s.title.trim(), s.pattern.trim(), s.rule)
            created = true
        }
        return id
    }

    private fun busy(block: suspend () -> Unit) {
        update { it.copy(busy = true, message = null) }
        launchIo {
            try {
                block()
            } catch (e: ApiError) {
                update { it.copy(message = fail(e)) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    override fun testRule() = busy {
        val id = ensurePoint()
        if (id != null) {
            val t = port.testRule(id, current.rule.trim())
            update { it.copy(test = t) }
        }
    }

    override fun save() {
        val span = current.span
        if (span == null) {
            update { it.copy(message = UiText(R.string.gc_need_span)) }
            return
        }
        busy {
            val id = ensurePoint()
            if (id != null) {
                port.putSpan(id, sentenceId, span.first, span.last + 1, mediaId)
                val s = current
                val c = s.chosen
                if (s.existing && c != null && s.rule.isNotBlank()) port.appendRule(c, s.rule)
                update { it.copy(done = true) }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
