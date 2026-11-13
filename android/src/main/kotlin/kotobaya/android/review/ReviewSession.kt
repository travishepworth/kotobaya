package kotobaya.android.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotobaya.android.grammar.GrammarSentenceSource
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.fsrs.RatingOutcome
import kotobaya.contracts.fsrs.Scheduler
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.render.CardRenderer
import kotobaya.contracts.render.CardSide
import kotobaya.contracts.render.DictionaryData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.fsrs.FsrsScheduler
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.PendingState

/** The queue the session pulls from (T304a's `LocalQueueLoader` in production). */
fun interface QueueSource {
    fun load(newShownThisSession: Int): ReviewQueue
}

/** Dictionary data for a vocab entry (T302 on-device dictionary), or null when unavailable. */
fun interface DictionaryLookup {
    fun lookup(entrySeq: Long): DictionaryData?
}

/** One card on screen, both sides rendered so flipping needs no work. */
data class ShownCard(
    /** Changes with every display: an answer is accepted once per token (request-id idempotency). */
    val token: Long,
    val cardId: String,
    val kind: QueueItemKind,
    val front: RenderedCard,
    val back: RenderedCard,
    /** One outcome per rating (Again, Hard, Good, Easy) from core's [Scheduler.preview]. */
    val previews: List<RatingOutcome>,
    /** Grammar cards: the example sentence shown (logged as `review.logged.sentence_id`), else null. */
    val sentenceId: String? = null,
    /** Grammar card with no usable example sentence on this device: the point's pattern for the i18n notice; else null. */
    val noExamplePattern: String? = null,
)

/** What the screen shows. */
sealed interface ReviewState {
    data object Loading : ReviewState

    /** [side] is the visible side. */
    data class Showing(val card: ShownCard, val side: CardSide) : ReviewState

    /** Nothing to review and nothing answered. */
    data object Empty : ReviewState

    data class Summary(val stats: SessionStats) : ReviewState

    /** The answer was refused (nothing written); the card stays so the user can retry after fixing the cause. */
    data class Blocked(val reason: MintRefusal, val card: ShownCard?) : ReviewState
}

/** Counters of the session, shown in the summary. */
data class SessionStats(val answered: Int, val byButton: Map<Button, Int>, val totalMs: Long)

/** Observable bits besides [ReviewState]. */
data class ReviewMeta(
    val canUndo: Boolean = false,
    val furigana: Boolean = true,
    /** Ops waiting in the outbox; the screen prompts a sync when it is at least [ReviewSession.SYNC_PROMPT_AT]. */
    val pendingOps: Int = 0,
    val answered: Int = 0,
)

/**
 * One review session (SPEC Clients: Android review). Everything it changes goes through
 * [OpMinter.mint] (`review.logged`, `review.undone`), whose single transaction also applies the
 * local effect (the card cache fold or replay of T303b) and queues the op; the session then rebuilds
 * the queue, so a crash after an answer leaves the op and the cache committed together.
 *
 * Thread-safe: every entry point takes one lock. The UI calls them off the main thread.
 */
class ReviewSession<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    private val queue: QueueSource,
    private val clock: () -> Long,
    private val utcOffsetMin: (Long) -> Int,
    private val newReviewId: (Long) -> String,
    private val prefs: () -> ReviewPrefs,
    private val renderer: CardRenderer = DefaultCardRenderer,
    private val scheduler: Scheduler = FsrsScheduler,
    private val dictionary: DictionaryLookup = DictionaryLookup { null },
    private val mediaUrlTemplate: String = CardPage.MEDIA_URL_TEMPLATE,
) {
    private class Answered(val reviewId: String, val cardId: String, val kind: QueueItemKind, val button: Button, val durationMs: Long)

    private val lock = Any()
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    private val _meta = MutableStateFlow(ReviewMeta(furigana = prefs().furigana))

    /** The screen state. */
    val state: StateFlow<ReviewState> get() = _state

    /** Undo availability, furigana flag, outbox size. */
    val meta: StateFlow<ReviewMeta> get() = _meta

    private val undoStack = ArrayList<Answered>()
    private var newShown = 0
    private var nextToken = 0L
    private var shownAtMs = 0L
    private var current: ShownCard? = null
    private var currentSide = CardSide.FRONT
    private var furigana = prefs().furigana
    private val answeredTokens = HashSet<Long>()

    /** Starts (or restarts) the session from the current queue. */
    fun start() = synchronized(lock) { advance(null) }

    /** Front/back toggle. */
    fun flip() = synchronized(lock) {
        val card = current ?: return@synchronized
        currentSide = if (currentSide == CardSide.FRONT) CardSide.BACK else CardSide.FRONT
        _state.value = ReviewState.Showing(card, currentSide)
    }

    /** Per-session furigana toggle (the app setting is the initial value); re-renders the shown card. */
    fun toggleFurigana() = synchronized(lock) {
        furigana = !furigana
        _meta.value = _meta.value.copy(furigana = furigana)
        refreshLocked()
    }

    /** Re-reads and re-renders the card on screen (after its note was edited); the queue is not rebuilt. */
    fun refreshCurrent() = synchronized(lock) { refreshLocked() }

    private fun refreshLocked() {
        val card = current ?: return
        val kind = card.kind
        val side = currentSide
        val reloaded = load(card.cardId, kind, card.token) ?: return
        current = reloaded
        currentSide = side
        _state.value = ReviewState.Showing(reloaded, side)
    }

    /**
     * Answers the card shown with [token] by [button]: mints exactly one `review.logged` however often it is
     * called with the same token (a double tap, a swipe plus a tap). Returns whether an op was minted.
     */
    fun answer(token: Long, button: Button): Boolean = synchronized(lock) {
        val card = current ?: return@synchronized false
        if (card.token != token || token in answeredTokens) return@synchronized false
        answeredTokens += token
        val now = clock()
        val reviewId = newReviewId(now)
        val duration = (now - shownAtMs).coerceIn(0L, MAX_DURATION_MS)
        val type = if (card.kind == QueueItemKind.REVIEW) "scheduled" else "learning"
        val payload = JsonObject(
            linkedMapOf<String, JsonValue>(
                "id" to JsonString(reviewId),
                "card_id" to JsonString(card.cardId),
                "reviewed_at" to JsonNumber(now.toDouble()),
                "utc_offset_min" to JsonNumber(utcOffsetMin(now).toDouble()),
                "review_type" to JsonString(type),
                "rating" to JsonNumber(button.rating.value.toDouble()),
                "button" to JsonString(button.wire),
                "duration_ms" to JsonNumber(duration.toDouble()),
            ).also { m -> card.sentenceId?.let { m["sentence_id"] = JsonString(it) } },
        )
        when (val r = minter.mint(OpType.REVIEW_LOGGED, payload)) {
            is MintResult.Refused -> {
                answeredTokens -= token
                _state.value = ReviewState.Blocked(r.reason, card)
                return@synchronized false
            }
            is MintResult.Minted -> Unit
        }
        undoStack += Answered(reviewId, card.cardId, card.kind, button, duration)
        if (card.kind == QueueItemKind.NEW) newShown++
        advance(null)
        true
    }

    /** Undoes the last answer of this session (`review.undone`) and shows that card again, first. No-op when nothing is left to undo. */
    fun undo(): Boolean = synchronized(lock) {
        val last = undoStack.lastOrNull() ?: return@synchronized false
        val payload = JsonObject(linkedMapOf<String, JsonValue>("review_id" to JsonString(last.reviewId)))
        when (val r = minter.mint(OpType.REVIEW_UNDONE, payload)) {
            is MintResult.Refused -> {
                _state.value = ReviewState.Blocked(r.reason, current)
                return@synchronized false
            }
            is MintResult.Minted -> Unit
        }
        undoStack.removeAt(undoStack.lastIndex)
        if (last.kind == QueueItemKind.NEW) newShown--
        advance(QueueItem(last.cardId, last.kind, null))
        true
    }

    private fun advance(front: QueueItem?) {
        val candidates = ArrayList<QueueItem>()
        front?.let { candidates += it }
        candidates += queue.load(newShown).items
        var shown: ShownCard? = null
        for (item in candidates) {
            shown = load(item.cardId, item.kind, ++nextToken)
            if (shown != null) break
        }
        current = shown
        currentSide = CardSide.FRONT
        shownAtMs = clock()
        val pending = pendingOps()
        _meta.value = _meta.value.copy(canUndo = undoStack.isNotEmpty(), pendingOps = pending, answered = undoStack.size)
        _state.value = when {
            shown != null -> ReviewState.Showing(shown, CardSide.FRONT)
            undoStack.isNotEmpty() -> ReviewState.Summary(stats())
            else -> ReviewState.Empty
        }
    }

    private fun stats() = SessionStats(
        answered = undoStack.size,
        byButton = undoStack.groupingBy { it.button }.eachCount(),
        totalMs = undoStack.sumOf { it.durationMs },
    )

    private fun pendingOps(): Int = store.transaction { it.outbox.count(PendingState.PENDING) }

    /** Reads and renders [cardId]; null if it cannot be shown (missing, deleted, merged, or holding the refused cache). */
    private fun load(cardId: String, kind: QueueItemKind, token: Long): ShownCard? = store.transaction { tx ->
        val t = tx.learning
        val card = CardRows.get(t, LearningTable.CARD, cardId) ?: return@transaction null
        if (!CardRows.live(card) || CardRows.refused(card)) return@transaction null
        val note = CardRows.get(t, LearningTable.NOTE, CardRows.str(card["note_id"]) ?: return@transaction null)
            ?: return@transaction null
        if (!CardRows.live(note)) return@transaction null
        val type = CardRows.get(t, LearningTable.NOTE_TYPE, CardRows.str(note["note_type_id"]) ?: return@transaction null)
            ?: return@transaction null
        val p = prefs()
        val entry = (note["entry_seq"] as? JsonNumber)?.value?.toLong()
        val cardKind = CardRows.str(card["kind"]) ?: ""
        val pointId = CardRows.str(note["grammar_point_id"])
        val grammarPoint = pointId?.takeIf { cardKind != "production" }
        val cloze = grammarPoint?.let { GrammarSentenceSource.pick(t, cardId, it, (card["reps"] as? JsonNumber)?.value?.toInt() ?: 0) }
        val pattern = grammarPoint?.takeIf { cloze == null }
            ?.let { CardRows.str(t.get(LearningTable.GRAMMAR_POINT, listOf(JsonString(it)))?.get("pattern")).orEmpty() }
        fun input(side: CardSide) = RenderInput(
            noteType = CardRows.noteTypeInfo(type),
            kind = CardRows.str(card["kind"]) ?: "",
            fields = CardRows.fieldsOf(note),
            mnemonic = CardRows.str(note["mnemonic"]).orEmpty(),
            dictionary = entry?.let(dictionary::lookup),
            cloze = cloze,
            side = side,
            options = RenderOptions(furigana = furigana, pitch = p.pitch, showMnemonic = true, mediaUrlTemplate = mediaUrlTemplate),
        )
        val now = clock()
        val previews = scheduler.preview(
            CardRows.scheduleOf(card), now, scheduler.paramsOf(CardRows.presetOf(t, card)), null, utcOffsetMin(now),
        )
        ShownCard(token, cardId, kind, renderer.render(input(CardSide.FRONT)), renderer.render(input(CardSide.BACK)), previews, cloze?.sentenceId, pattern)
    }

    companion object {
        /** `review.logged.duration_ms` cap (schema). */
        const val MAX_DURATION_MS = 600_000L

        /** The screen prompts a sync when at least this many ops wait. */
        const val SYNC_PROMPT_AT = 50
    }
}
