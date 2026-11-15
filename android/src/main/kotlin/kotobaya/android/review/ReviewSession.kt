package kotobaya.android.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotobaya.android.vm.ScreenLog
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
import kotobaya.contracts.render.KanjiData
import kotobaya.contracts.render.RenderInput
import kotobaya.contracts.render.RenderOptions
import kotobaya.contracts.render.RenderedCard
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.fsrs.FsrsScheduler
import kotobaya.render.DefaultCardRenderer
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction

/** A built queue plus the two rules the session needs to keep it current between builds (AND-REVIEW-SNAPPY). */
data class SessionQueue(
    val queue: ReviewQueue,
    /** `scheduler.bury_siblings`: a NEW/REVIEW card whose note was answered this session is skipped. */
    val burySiblings: Boolean = false,
    /** C-26: the queue is empty but a keep-going build has cards. */
    val moreAvailable: Boolean = false,
    /** C-26: the study day's start of the build (keep going ends when it changes); 0 when the source does not know. */
    val dayStartMs: Long = 0,
    /** ADR-033: the study day's end of the build (a learning card due at or after it does not make the session wait). */
    val dayEndMs: Long = Long.MAX_VALUE,
)

/** The queue the session pulls from (T304a's `LocalQueueLoader` in production). */
fun interface QueueSource {
    fun load(newShownThisSession: Int): ReviewQueue

    /** [load] with the rules of the build; sources without them get the defaults. */
    fun loadSession(newShownThisSession: Int, keepGoing: Boolean = false): SessionQueue = SessionQueue(load(newShownThisSession))

    /** The same source limited to one deck (Decks tab "Study this deck"); the default ignores the deck. */
    fun forDeck(deckId: String): QueueSource = this
}

/**
 * How the session reads the store outside its own writes: rendering, prefetching and requeueing must not wait for an
 * answer's write transaction (production: SQLite reader connections, [kotobaya.android.sync.SqlDelightDeviceStore.read]).
 */
interface ReviewReads<L> {
    fun <R> read(block: (StoreTransaction<L>) -> R): R
}

/** Dictionary data for a vocab entry (T302 on-device dictionary), or null when unavailable. */
fun interface DictionaryLookup {
    fun lookup(entrySeq: Long): DictionaryData?
}

/** Kanji data for a kanji note's `kanji_literal` (the on-device dictionary's `kanji` table), or null when unavailable. */
fun interface KanjiLookup {
    fun lookup(literal: String): KanjiData?
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
    /** The card's note (sibling burying between queue builds). */
    val noteId: String = "",
    /** C-25: the back side's example words, deck chips and "Open in dictionary" target (computed with the card). */
    val extras: BackExtras = BackExtras(),
)

/** What the screen shows. */
sealed interface ReviewState {
    data object Loading : ReviewState

    /** [side] is the visible side. */
    data class Showing(val card: ShownCard, val side: CardSide) : ReviewState

    /** Nothing to review and nothing answered. */
    data object Empty : ReviewState

    /**
     * ADR-033: an all-decks session (normal or keep going) has nothing due now, but learning cards fall due later
     * today. [nextDueAt] is the earliest; the screen counts down to it and resumes the session then (no learn-ahead:
     * a learning card is never shown before it is due). [stats] is null when nothing was answered yet.
     */
    data class Waiting(val stats: SessionStats?, val nextDueAt: Long) : ReviewState

    /** The queue could not be loaded or the next card could not be shown (a storage error); the screen offers Retry. */
    data object Failed : ReviewState

    data class Summary(val stats: SessionStats) : ReviewState

    /**
     * AND-DECKEND: a deck session has nothing due in its deck right now. [stats] is null when nothing was answered.
     * [nextLearningDueAt]: the earliest instant a learning card of the deck falls due (null if none today).
     * [othersDue]: the all-decks queue has cards due now ([ReviewSession.continueWithAll] switches to it).
     */
    data class DeckDone(val stats: SessionStats?, val nextLearningDueAt: Long?, val othersDue: Boolean) : ReviewState

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
    /** C-26: this session is in keep-going mode (past the daily limits). */
    val keepingGoing: Boolean = false,
    /** C-26: when the session is out of cards, a keep-going build would have more ("done for now" + Keep going). */
    val moreAvailable: Boolean = false,
)

/**
 * The answer could not be written (a storage error). The session has already rolled the screen back to that card,
 * answerable again; nothing of it (or of any answer tapped after it) was written.
 */
class ReviewCommitFailed(cause: Throwable) : RuntimeException("review answer not committed", cause)

/**
 * One review session (SPEC Clients: Android review). Everything it changes goes through
 * [OpMinter.mint] (`review.logged`, `review.undone`), whose single transaction also applies the
 * local effect (the card cache fold or replay of T303b) and queues the op.
 *
 * AND-REVIEW-SNAPPY: the next card comes from the session's in-memory queue (built once, then kept current
 * incrementally), pre-rendered while the current one is showing; [answer] publishes it *before* the op is written,
 * then writes the op on the calling (IO) thread and returns only once it is committed. Answers are written strictly
 * in tap order by one writer at a time ([commitLock]); an answer stays in the session (undo stack, pending queue)
 * until its transaction has committed. If a write fails, the screen goes back to that card and every answer tapped
 * after it is withdrawn (never written), so nothing is lost silently and nothing is written twice.
 *
 * Locks: [commitLock] (one writer, queue builds) is always taken before [lock] (the in-memory state, held only
 * briefly). The UI calls every entry point off the main thread.
 */
class ReviewSession<L : LearningTables>(
    private val store: DeviceStore<L>,
    private val minter: OpMinter<L>,
    queue: QueueSource,
    private val clock: () -> Long,
    private val utcOffsetMin: (Long) -> Int,
    private val newReviewId: (Long) -> String,
    private val prefs: () -> ReviewPrefs,
    private val renderer: CardRenderer = DefaultCardRenderer,
    private val scheduler: Scheduler = FsrsScheduler,
    private val dictionary: DictionaryLookup = DictionaryLookup { null },
    private val kanji: KanjiLookup = KanjiLookup { null },
    private val kanjiWords: KanjiWordSource = KanjiWordSource { emptyList() },
    /** C-25: the per-build learning inputs of the back extras; null reads them through [LearningTables] ([BackExtrasIndex.from]). */
    private val extrasSource: BackExtrasIndexSource? = null,
    private val mediaUrlTemplate: String = CardPage.MEDIA_URL_TEMPLATE,
    private val timings: (ReviewTiming) -> Unit = ReviewTiming.LOG,
    /** A full queue build runs after an answer once the last one is this old (cards that became due meanwhile, sync). */
    private val rebuildEveryMs: Long = REBUILD_EVERY_MS,
    /**
     * Concurrent reads; null means the store is not safe for concurrent use (the in-memory store of tests and the
     * demo): every read and write of the session then goes through one lock.
     */
    private val reads: ReviewReads<L>? = null,
    /**
     * AND-DECKEND: non-null makes this a deck session ([queue] is the deck's) and is the all-decks queue. A deck
     * session with nothing due now ends in [ReviewState.DeckDone], from which [continueWithAll] switches to this queue.
     */
    allDue: QueueSource? = null,
) {
    private enum class Status { PENDING, COMMITTED, REFUSED, FAILED, WITHDRAWN }

    /** One answer, from the tap until its op is committed (or it is withdrawn). */
    private class Commit(
        val reviewId: String,
        val card: ShownCard,
        val side: CardSide,
        val button: Button,
        val durationMs: Long,
        val payload: JsonObject,
        val tapNs: Long,
    ) {
        @Volatile var status: Status = Status.PENDING
        var error: Throwable? = null
        /** Tap to the next card (or the summary) being published. */
        var advanceMs = 0.0
        var selectMs = 0.0
        var renderMs = 0.0
        var prefetchHit = false
        var skipped = 0
        var commitMs = 0.0
        var requeueMs = 0.0
    }

    /** A learning card in the session queue: shown only once due (ADR-033: no learn-ahead). */
    private class Timed(val item: QueueItem, val dueAt: Long)

    private val lock = Any()
    private val commitLock = Any()

    /** Innermost lock; only used without [reads]. Never held while taking [lock] or [commitLock]. */
    private val storeLock = Any()

    private fun <R> read(block: (StoreTransaction<L>) -> R): R =
        if (reads != null) reads.read(block) else synchronized(storeLock) { store.transaction(block) }

    private fun mint(type: OpType, payload: JsonObject): MintResult =
        if (reads != null) minter.mint(type, payload) else synchronized(storeLock) { minter.mint(type, payload) }
    private val _state = MutableStateFlow<ReviewState>(ReviewState.Loading)
    private val _meta = MutableStateFlow(ReviewMeta(furigana = prefs().furigana))

    /** The screen state. */
    val state: StateFlow<ReviewState> get() = _state

    /** Undo availability, furigana flag, outbox size. */
    val meta: StateFlow<ReviewMeta> get() = _meta

    // --- guarded by lock ---
    /** Answers of this session in tap order, committed or still pending. */
    private val undoStack = ArrayList<Commit>()

    /** Answers not yet committed, in tap order (a prefix-ordered tail of [undoStack]). */
    private val pending = ArrayDeque<Commit>()

    /** NEW/REVIEW (and build-time learning-now) items in the builder's order. */
    private val main = ArrayDeque<QueueItem>()

    /** Learning cards with their due instant. */
    private val learning = ArrayList<Timed>()
    private var burySiblings = false
    private var dayEndMs = Long.MAX_VALUE
    private var lastBuildAt = Long.MIN_VALUE

    /** The queue the session builds from; becomes the all-decks queue on [continueWithAll]. */
    private var source: QueueSource = queue

    /** Deck session: the all-decks queue; null for an all-decks session. */
    private var allDecks: QueueSource? = allDue

    /** Deck session: the last build found cards due now outside what the session holds. */
    private var othersDue = false

    /** The last build's `nextLearningDueAt` ([ReviewState.Waiting], [ReviewState.DeckDone]). */
    private var buildNextLearningDueAt: Long? = null

    /** Bumped whenever the queue or rendering inputs change: an older pre-render is dropped. */
    private var generation = 0L

    /** Pre-rendered candidates for the next card, by card id (at most [PREFETCH_MAX]). */
    private val prefetched = HashMap<String, ShownCard>()

    /** C-25: read once per queue build (see [rebuild]) and shared by every card's back extras until the next one. */
    @Volatile private var extrasIndex: BackExtrasIndex? = null

    /** This answer emptied the in-memory queue: the next card is chosen once the pending answers are committed. */
    private var awaitingFor: Commit? = null

    /** Answers committed so far, and how many of them the last build saw. */
    private var commitCount = 0L
    private var commitsAtBuild = 0L
    private var newShown = 0

    /** C-26: keep-going mode of this session (never persisted), and the study day it started in. */
    private var keepGoing = false
    private var keepGoingDay: Long? = null
    private var buildMoreAvailable = false
    private var nextToken = 0L
    private var shownAtMs = 0L
    private var current: ShownCard? = null
    private var currentSide = CardSide.FRONT
    private val answeredTokens = HashSet<Long>()

    @Volatile
    private var furigana = prefs().furigana

    /** Starts (or restarts) the session from a fresh queue build; waits for pending answers first. */
    fun start() {
        synchronized(commitLock) {
            drainLocked()
            synchronized(lock) {
                current = null
                awaitingFor = null
            }
            rebuild("start")
            synchronized(lock) { if (!showFromMemoryLocked(null)) finishLocked() }
        }
        refreshPendingOps()
        prefetch()
    }

    /** C-26: switches this (all-decks) session to keep-going mode and rebuilds the queue. No-op for a deck session. */
    fun startKeepGoing() {
        if (allDecks != null) return
        synchronized(lock) {
            keepGoing = true
            keepGoingDay = null
        }
        start()
    }

    /** Front/back toggle. */
    fun flip() {
        val t0 = System.nanoTime()
        val revealed = synchronized(lock) {
            val card = current ?: return
            currentSide = if (currentSide == CardSide.FRONT) CardSide.BACK else CardSide.FRONT
            _state.value = ReviewState.Showing(card, currentSide)
            currentSide == CardSide.BACK
        }
        if (revealed) timings(ReviewTiming.Reveal(ReviewTiming.ms(t0)))
    }

    /** Per-session furigana toggle (the app setting is the initial value); re-renders the shown card. */
    fun toggleFurigana() {
        synchronized(lock) {
            furigana = !furigana
            _meta.update { it.copy(furigana = furigana) }
            refreshLocked()
        }
        prefetch()
    }

    /** Re-reads and re-renders the card on screen (after its note was edited); the queue is not rebuilt. */
    fun refreshCurrent() {
        synchronized(lock) { refreshLocked() }
        prefetch()
    }

    /** The due state may have changed outside the session (a sync pulled ops): the next answer rebuilds the queue. */
    fun markStale() = synchronized(lock) { lastBuildAt = Long.MIN_VALUE }

    private fun refreshLocked() {
        generation++
        prefetched.clear()
        val card = current ?: return
        val side = currentSide
        val reloaded = load(card.cardId, card.kind, card.token) ?: return
        current = reloaded
        currentSide = side
        _state.value = ReviewState.Showing(reloaded, side)
    }

    /**
     * Answers the card shown with [token] by [button]: mints exactly one `review.logged` however often it is
     * called with the same token (a double tap, a swipe plus a tap). The next card is on screen before the op is
     * written; the call returns once it is committed: true then, false if it was not accepted, refused
     * ([ReviewState.Blocked]) or withdrawn. Throws [ReviewCommitFailed] if writing it failed (the screen is back on
     * this card).
     */
    fun answer(token: Long, button: Button): Boolean {
        val tap = System.nanoTime()
        val commit = synchronized(lock) {
            val card = current ?: return false
            if (card.token != token || token in answeredTokens) return false
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
            val c = Commit(reviewId, card, currentSide, button, duration, payload, tap)
            pending.addLast(c)
            undoStack += c
            if (card.kind == QueueItemKind.NEW) newShown++
            // The answer is held in `pending` until committed; showing the next card cannot lose it.
            try {
                if (!showFromMemoryLocked(c)) {
                    // Nothing in memory (the end of the queue, or only this card's own learning step is left): the
                    // answered card stays drawn, no longer answerable, until its write decides what comes next. No
                    // Loading state: the screen keeps its one WebView instead of tearing it down for a few ms.
                    awaitingFor = c
                    current = null
                }
            } catch (e: Exception) {
                ScreenLog.loadFailed("ReviewSession.answer", e)
                current = null
                _state.value = ReviewState.Failed
                c.advanceMs = ReviewTiming.ms(tap)
            }
            c
        }
        var rebuildMs: Double? = null
        synchronized(commitLock) {
            drainLocked()
            rebuildMs = settleLocked()
        }
        refreshPendingOps()
        val prefetchMs = prefetch()
        timings(
            ReviewTiming.Answer(
                kind = commit.card.kind.name.lowercase(), advanceMs = commit.advanceMs, selectMs = commit.selectMs,
                renderMs = commit.renderMs, prefetchHit = commit.prefetchHit, skipped = commit.skipped, commitMs = commit.commitMs,
                requeueMs = commit.requeueMs, prefetchMs = prefetchMs, rebuildMs = rebuildMs,
                outcome = commit.status.name.lowercase(),
            ),
        )
        commit.error?.let { throw ReviewCommitFailed(it) }
        return commit.status == Status.COMMITTED
    }

    /** Writes every pending answer, oldest first. Caller holds [commitLock]. */
    private fun drainLocked() {
        while (true) {
            val c = synchronized(lock) { pending.firstOrNull() } ?: return
            val t0 = System.nanoTime()
            val result: Any = try {
                mint(OpType.REVIEW_LOGGED, c.payload)
            } catch (e: Exception) {
                e
            }
            c.commitMs = ReviewTiming.ms(t0)
            if (result is MintResult.Minted) {
                // committed: the card's new schedule decides whether it comes back this session
                val t1 = System.nanoTime()
                val row = try {
                    read { CardRows.get(it.learning, LearningTable.CARD, c.card.cardId) }
                } catch (e: Exception) {
                    ScreenLog.loadFailed("ReviewSession.requeue", e)
                    null
                }
                synchronized(lock) {
                    pending.removeFirst()
                    c.status = Status.COMMITTED
                    commitCount++
                    requeueLocked(c.card, row)
                }
                c.requeueMs = ReviewTiming.ms(t1)
            } else {
                synchronized(lock) {
                    pending.removeFirst()
                    rollbackLocked(c, result)
                }
            }
        }
    }

    /**
     * After a drain (caller holds [commitLock]): the next card if the last answer emptied the in-memory queue, and
     * a full build while a card is showing when the last one is old ("refresh") or nothing comes after the card on
     * screen ("ahead"), so the end of the queue never waits for a build. Returns the build's ms, or null.
     */
    private fun settleLocked(): Double? {
        var rebuildMs: Double? = null
        synchronized(lock) {
            if (pending.isNotEmpty()) return null
            val waiting = awaitingFor ?: return@synchronized
            showFromMemoryLocked(waiting)
            // Nothing in memory: always rebuild before ending. No earlier build is final: a normal one that still held
            // the answered card cannot say whether keep going has more, a deck session needs to know whether other
            // decks have cards due, and a keep-going build holds only one new batch (AND-KEEPGOING-FIX), so the
            // session ends only when a fresh build has nothing to show.
        }
        if (synchronized(lock) { awaitingFor != null }) {
            rebuildMs = rebuild("end of queue")
            synchronized(lock) {
                val waiting = awaitingFor
                if (waiting != null && pending.isEmpty() && !showFromMemoryLocked(waiting)) finishLocked()
            }
            return rebuildMs
        }
        val why = synchronized(lock) {
            when {
                current == null -> null
                staleLocked() -> "refresh"
                peekLocked(clock()) == null && commitCount > commitsAtBuild -> "ahead"
                else -> null
            }
        }
        if (why != null) rebuildMs = rebuild(why)
        return rebuildMs
    }

    private fun staleLocked(): Boolean = clock() - lastBuildAt >= rebuildEveryMs

    /** A committed answer: the card leaves the queue, and comes back as a learning item if its new schedule says so. */
    private fun requeueLocked(card: ShownCard, row: Row?) {
        val id = card.cardId
        main.removeAll { it.cardId == id }
        learning.removeAll { it.item.cardId == id }
        prefetched.remove(id)
        generation++
        if (row == null || !CardRows.live(row) || CardRows.refused(row)) return
        if ((row["suspended"] as? JsonBool)?.value == true) return
        val state = CardRows.str(row["state"])
        if (state != "learning" && state != "relearning") return
        val due = (row["due_at"] as? JsonNumber)?.value?.toLong() ?: Long.MIN_VALUE
        learning += Timed(QueueItem(id, QueueItemKind.LEARNING, null), due)
    }

    /**
     * The write of [failed] was refused ([result] a [MintResult.Refused]) or threw: nothing of it was written. Every
     * answer tapped after it is withdrawn (never written), and the screen goes back to [failed]'s card.
     */
    private fun rollbackLocked(failed: Commit, result: Any) {
        val later = pending.toList()
        pending.clear()
        for (c in later) c.status = Status.WITHDRAWN
        val dropped = listOf(failed) + later
        undoStack.removeAll { u -> dropped.any { it === u } }
        for (c in dropped) {
            answeredTokens -= c.card.token
            if (c.card.kind == QueueItemKind.NEW) newShown--
        }
        current?.let { main.addFirst(itemOf(it)) }
        for (c in later.asReversed()) main.addFirst(itemOf(c.card))
        generation++
        prefetched.clear()
        awaitingFor = null
        current = failed.card
        currentSide = failed.side
        shownAtMs = clock()
        updateMetaLocked()
        if (result is MintResult.Refused) {
            failed.status = Status.REFUSED
            _state.value = ReviewState.Blocked(result.reason, failed.card)
        } else {
            failed.status = Status.FAILED
            failed.error = result as? Throwable ?: IllegalStateException("mint failed: $result")
            ScreenLog.loadFailed("ReviewSession.commit", failed.error ?: IllegalStateException())
            _state.value = ReviewState.Showing(failed.card, failed.side)
        }
    }

    /** Undoes the last answer of this session (`review.undone`) and shows that card again, first. No-op when nothing is left to undo. */
    fun undo(): Boolean {
        val done = synchronized(commitLock) { undoLocked() }
        if (done) {
            refreshPendingOps()
            prefetch()
        }
        return done
    }

    private fun undoLocked(): Boolean {
        drainLocked()
        val last = synchronized(lock) {
            val l = undoStack.lastOrNull() ?: return false
            if (l.status == Status.PENDING) {
                // tapped after the drain and not written yet (no write runs while commitLock is held): withdraw it
                pending.removeAll { it === l }
                l.status = Status.WITHDRAWN
                undoStack.removeAt(undoStack.lastIndex)
                answeredTokens -= l.card.token
                if (l.card.kind == QueueItemKind.NEW) newShown--
                backToFrontLocked(l)
                return true
            }
            l
        }
        val payload = JsonObject(linkedMapOf<String, JsonValue>("review_id" to JsonString(last.reviewId)))
        when (val r = mint(OpType.REVIEW_UNDONE, payload)) {
            is MintResult.Refused -> {
                synchronized(lock) { _state.value = ReviewState.Blocked(r.reason, current) }
                return false
            }
            is MintResult.Minted -> Unit
        }
        synchronized(lock) {
            undoStack.removeAll { it === last }
            if (last.card.kind == QueueItemKind.NEW) newShown--
            backToFrontLocked(last)
        }
        return true
    }

    /** Undo: [c]'s card is shown again (re-rendered: its schedule changed back), the card on screen goes back to the queue's head. */
    private fun backToFrontLocked(c: Commit) {
        val id = c.card.cardId
        main.removeAll { it.cardId == id }
        learning.removeAll { it.item.cardId == id }
        current?.let { main.addFirst(itemOf(it)) }
        current = null
        awaitingFor = null
        generation++
        prefetched.clear()
        if (!showFromMemoryLocked(null, front = itemOf(c.card))) finishLocked()
    }

    private fun itemOf(card: ShownCard) = QueueItem(card.cardId, card.kind, null)

    /**
     * Shows the next card held in memory ([front] first if given); false when there is none. [answered] (when the
     * call comes from an answer) records how long selection and rendering took.
     */
    private fun showFromMemoryLocked(answered: Commit?, front: QueueItem? = null): Boolean {
        var head = front
        val now = clock()
        while (true) {
            val t0 = System.nanoTime()
            val item = head?.also { head = null } ?: pickLocked(now) ?: return false
            val t1 = System.nanoTime()
            val pre = prefetched.remove(item.cardId)?.takeIf { it.kind == item.kind }
            val shown = pre?.copy(token = ++nextToken) ?: load(item.cardId, item.kind, ++nextToken)
            answered?.let {
                it.selectMs += ReviewTiming.ms(t0, t1)
                it.renderMs += ReviewTiming.ms(t1)
                it.prefetchHit = pre != null
            }
            if (shown == null || siblingAnsweredLocked(shown)) {
                answered?.let { it.skipped++ }
                continue
            }
            answered?.let { it.advanceMs = ReviewTiming.ms(it.tapNs) }
            current = shown
            currentSide = CardSide.FRONT
            shownAtMs = clock()
            awaitingFor = null
            updateMetaLocked()
            _state.value = ReviewState.Showing(shown, CardSide.FRONT)
            return true
        }
    }

    /**
     * Nothing due now after a fresh build: a deck session is [ReviewState.DeckDone]; an all-decks session waits for its
     * next learning card when one falls due later today ([ReviewState.Waiting], ADR-033), else it is the summary, or
     * Empty when nothing was answered.
     */
    private fun finishLocked() {
        awaitingFor?.let { it.advanceMs = ReviewTiming.ms(it.tapNs) }
        current = null
        awaitingFor = null
        updateMetaLocked()
        val stats = if (undoStack.isNotEmpty()) stats() else null
        val next = (learning.map { it.dueAt }.filter { it < dayEndMs } + listOfNotNull(buildNextLearningDueAt)).minOrNull()
        _state.value = when {
            allDecks != null -> ReviewState.DeckDone(stats, next, othersDue)
            next != null -> ReviewState.Waiting(stats, next)
            stats != null -> ReviewState.Summary(stats)
            else -> ReviewState.Empty
        }
    }

    /**
     * AND-DECKEND: "Continue with all due cards" — the deck session becomes an all-decks session (answers, undo and
     * counters carry over). No-op for an all-decks session.
     */
    fun continueWithAll() {
        synchronized(commitLock) {
            drainLocked()
            synchronized(lock) {
                val all = allDecks ?: return
                source = all
                allDecks = null
                othersDue = false
                current = null
                awaitingFor = null
            }
            rebuild("all decks")
            synchronized(lock) { if (!showFromMemoryLocked(null)) finishLocked() }
        }
        refreshPendingOps()
        prefetch()
    }

    private fun updateMetaLocked() {
        _meta.update {
            it.copy(canUndo = undoStack.isNotEmpty(), answered = undoStack.size, keepingGoing = keepGoing, moreAvailable = buildMoreAvailable)
        }
    }

    /**
     * Builder rule 2 over time: learning due now, then the built order. ADR-033: a learning card not yet due is never
     * shown, whatever else is left (no learn-ahead in any session; phone log 2026-11-15: cards answered Again came back
     * every few seconds at the end of the queue, and 10-minute steps were cut to one minute).
     */
    private fun pickLocked(now: Long): QueueItem? {
        learning.filter { it.dueAt <= now }.minWithOrNull(TIMED_ORDER)?.let { learning.remove(it); return it.item }
        return main.removeFirstOrNull()
    }

    private fun peekLocked(now: Long): QueueItem? =
        learning.filter { it.dueAt <= now }.minWithOrNull(TIMED_ORDER)?.item ?: main.firstOrNull()

    /** Builder rule 1 between builds: a NEW/REVIEW card whose note had another card answered this session. */
    private fun siblingAnsweredLocked(card: ShownCard): Boolean {
        if (!burySiblings || card.kind == QueueItemKind.LEARNING || card.noteId.isEmpty()) return false
        return undoStack.any { it.card.noteId == card.noteId && it.card.cardId != card.cardId }
    }

    /**
     * A full build of the queue (caller holds [commitLock], not [lock]): every committed answer is in it; the card on
     * screen and answers still pending are left out (the session knows their state better).
     */
    private fun rebuild(why: String): Double {
        val t0 = System.nanoTime()
        val (shownNew, from, all) = synchronized(lock) { Triple(newShown, source, allDecks) }
        var built = from.loadSession(shownNew, synchronized(lock) { keepGoing && allDecks == null })
        synchronized(lock) {
            if (keepGoing) {
                val started = keepGoingDay
                if (started == null) {
                    keepGoingDay = built.dayStartMs
                } else if (started != built.dayStartMs) {
                    // C-26: the study day changed: keep going ends, the normal queue is built
                    keepGoing = false
                    keepGoingDay = null
                    built = from.loadSession(shownNew, false)
                }
            }
        }
        val dues = duesOf(built.queue)
        val index = loadExtrasIndex()
        // deck session: the cards due now in every deck
        val dueEverywhere: List<String>? = all?.loadSession(shownNew)?.queue?.let { q ->
            val now = clock()
            val d = duesOf(q)
            q.items.filter { it.kind != QueueItemKind.LEARNING || (d[it.cardId] ?: Long.MIN_VALUE) <= now }.map { it.cardId }
        }
        synchronized(lock) {
            val exclude = HashSet<String>()
            current?.let { exclude += it.cardId }
            for (c in pending) exclude += c.card.cardId
            main.clear()
            learning.clear()
            for (item in built.queue.items) {
                if (item.cardId in exclude) continue
                if (item.kind == QueueItemKind.LEARNING) {
                    learning += Timed(item, dues[item.cardId] ?: Long.MIN_VALUE)
                } else {
                    main += item
                }
            }
            burySiblings = built.burySiblings
            dayEndMs = built.dayEndMs
            buildNextLearningDueAt = built.queue.nextLearningDueAt
            buildMoreAvailable = built.moreAvailable
            othersDue = dueEverywhere != null && dueEverywhere.any { it !in exclude }
            lastBuildAt = clock()
            commitsAtBuild = commitCount
            generation++
            prefetched.clear()
            extrasIndex = index
        }
        val ms = ReviewTiming.ms(t0)
        timings(ReviewTiming.Rebuild(why, ms, built.queue.items.size))
        return ms
    }

    /** `due_at` of the queue's learning items. */
    private fun duesOf(q: ReviewQueue): Map<String, Long> {
        val ids = q.items.filter { it.kind == QueueItemKind.LEARNING }.map { it.cardId }
        if (ids.isEmpty()) return emptyMap()
        return read { tx ->
            ids.associateWith { id ->
                (CardRows.get(tx.learning, LearningTable.CARD, id)?.get("due_at") as? JsonNumber)?.value?.toLong() ?: Long.MIN_VALUE
            }
        }
    }

    /**
     * Pre-renders (outside [lock]) the card that would be shown next, and the earliest learning card, which takes
     * its place if it falls due first. An item that turns out not to be showable is dropped from the queue here, so
     * the answer does not have to pass over it. Returns the ms spent (0 when both were ready).
     */
    private fun prefetch(): Double {
        val t0 = System.nanoTime()
        var worked = false
        repeat(PREFETCH_ROUNDS) {
            val (todo, gen) = synchronized(lock) {
                if (current == null) return if (worked) ReviewTiming.ms(t0) else 0.0
                val wanted = listOfNotNull(peekLocked(clock()), learning.minWithOrNull(TIMED_ORDER)?.item).distinctBy { it.cardId }
                prefetched.keys.retainAll(wanted.mapTo(HashSet()) { it.cardId })
                val missing = wanted.filter { w -> prefetched[w.cardId]?.kind != w.kind }
                if (missing.isEmpty()) return if (worked) ReviewTiming.ms(t0) else 0.0
                missing to generation
            }
            worked = true
            for (item in todo) {
                val shown = try {
                    load(item.cardId, item.kind, PREFETCH_TOKEN)
                } catch (e: Exception) {
                    return ReviewTiming.ms(t0) // a storage error surfaces when the card is due to be shown
                }
                synchronized(lock) {
                    if (gen != generation) return ReviewTiming.ms(t0)
                    if (shown == null || siblingAnsweredLocked(shown)) {
                        main.removeAll { it.cardId == item.cardId }
                        learning.removeAll { it.item.cardId == item.cardId }
                    } else if (prefetched.size < PREFETCH_MAX) {
                        prefetched[item.cardId] = shown
                    }
                }
            }
        }
        return ReviewTiming.ms(t0)
    }

    private fun stats() = SessionStats(
        answered = undoStack.size,
        byButton = undoStack.groupingBy { it.button }.eachCount(),
        totalMs = undoStack.sumOf { it.durationMs },
    )

    /** The outbox size for the sync prompt (off the answer's critical path). */
    private fun refreshPendingOps() {
        val n = try {
            read { it.outbox.count(PendingState.PENDING) }
        } catch (e: Exception) {
            return
        }
        _meta.update { it.copy(pendingOps = n) }
    }

    /** Reads and renders [cardId]; null if it cannot be shown (missing, deleted, merged, or holding the refused cache). */
    private fun load(cardId: String, kind: QueueItemKind, token: Long): ShownCard? {
        val index = extrasIndex ?: loadExtrasIndex()?.also { extrasIndex = it } ?: BackExtrasIndex.EMPTY
        return loadWith(index, cardId, kind, token)
    }

    /** The back extras' per-build inputs; null when they cannot be read (the extras then degrade, the card does not). */
    private fun loadExtrasIndex(): BackExtrasIndex? = try {
        extrasSource?.load() ?: read { BackExtrasIndex.from(it.learning) }
    } catch (e: Exception) {
        null
    }

    private fun loadWith(index: BackExtrasIndex, cardId: String, kind: QueueItemKind, token: Long): ShownCard? = read { tx ->
        val t = tx.learning
        val card = CardRows.get(t, LearningTable.CARD, cardId) ?: return@read null
        if (!CardRows.live(card) || CardRows.refused(card)) return@read null
        val noteId = CardRows.str(card["note_id"]) ?: return@read null
        val note = CardRows.get(t, LearningTable.NOTE, noteId) ?: return@read null
        if (!CardRows.live(note)) return@read null
        val type = CardRows.get(t, LearningTable.NOTE_TYPE, CardRows.str(note["note_type_id"]) ?: return@read null)
            ?: return@read null
        val p = prefs()
        val furiganaOn = furigana
        val entry = (note["entry_seq"] as? JsonNumber)?.value?.toLong()
        val dictData = entry?.let(dictionary::lookup)
        val kanjiData = CardRows.str(note["kanji_literal"])?.let(kanji::lookup)
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
            dictionary = dictData,
            cloze = cloze,
            side = side,
            options = RenderOptions(furigana = furiganaOn, pitch = p.pitch, showMnemonic = true, mediaUrlTemplate = mediaUrlTemplate),
            kanji = kanjiData,
        )
        val now = clock()
        val previews = scheduler.preview(
            CardRows.scheduleOf(card), now, scheduler.paramsOf(CardRows.presetOf(t, card)), null, utcOffsetMin(now),
        )
        ShownCard(
            token, cardId, kind, renderer.render(input(CardSide.FRONT)), renderer.render(input(CardSide.BACK)), previews,
            cloze?.sentenceId, pattern, noteId,
            // C-25: a failure here costs the extras, never the card
            runCatching { BackExtrasBuilder.build(t, note, kanjiData, kanjiWords, dictionary, index) }
                .getOrElse { BackExtras(entrySeq = entry, kanjiLiteral = CardRows.str(note["kanji_literal"])) },
        )
    }

    companion object {
        /** `review.logged.duration_ms` cap (schema). */
        const val MAX_DURATION_MS = 600_000L

        /** The screen prompts a sync when at least this many ops wait. */
        const val SYNC_PROMPT_AT = 50

        /** The in-memory queue is rebuilt from the database after an answer once the last build is this old. */
        const val REBUILD_EVERY_MS = 5 * 60_000L

        private const val PREFETCH_TOKEN = -1L
        private const val PREFETCH_MAX = 2
        private const val PREFETCH_ROUNDS = 4
        private val TIMED_ORDER = compareBy<Timed>({ it.dueAt }, { it.item.cardId })
    }
}
