package kotobaya.android.queue

import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Reserved
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.DeckMembership
import kotobaya.contracts.queue.PendingEntry
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.queue.TodayReview
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.queue.DefaultQueueBuilder
import kotobaya.sync.DefaultCanonicalJson

/** Counts shown on Home: what the queue holds right now (limits applied by core's builder). */
data class QueueCounts(
    val due: Int,
    val new: Int,
    val learning: Int,
    /** C-26: the queue is empty but keep going has more ("done for now"); Today offers Keep going. */
    val moreAvailable: Boolean = false,
) {
    /** Every item of the queue: what a review session started now can show. */
    val total: Int get() = due + new + learning

    companion object {
        val ZERO: QueueCounts = QueueCounts(0, 0, 0)
    }
}

/** A built queue with the exact input that produced it. */
class LoadedQueue internal constructor(
    internal val input: QueueInput,
    val queue: ReviewQueue,
) {
    val counts: QueueCounts
        get() = QueueCounts(
            due = queue.items.count { it.kind == QueueItemKind.REVIEW },
            // entries still to materialize (no materializer, or it refused) are new cards of this queue too
            new = queue.items.count { it.kind == QueueItemKind.NEW } + queue.toMaterialize.size,
            learning = queue.items.count { it.kind == QueueItemKind.LEARNING },
            moreAvailable = queue.moreAvailable,
        )

    /** The first [size] items: what a review session starts with. */
    fun sessionPrefix(size: Int): List<QueueItem> = queue.items.take(size)
}

/** Card ids a smart deck selects (needs the search evaluator on SQLite; none until that ships). */
fun interface SmartDeckResolver {
    fun cardIds(deckId: String, nowMs: Long, day: StudyDay): List<String>
}

/**
 * The platform loader of the core queue builder (QueueBuilder.kt, SCHEMA.md 6), mirroring the server's QueueLoader so
 * both build the identical queue from identical data. It applies no queue rule itself; what it decides is what is
 * "live" for the inputs:
 * - decks are the live ones; a membership naming a deleted or unknown deck is dropped;
 * - candidates are live cards of live notes (not merge losers), not suspended, and never a card holding the refused
 *   cache (`last_review_id` = `Reserved.REPLAY_REFUSED_ID`, SYNC.md 9.4); such a card stays findable by search;
 * - `add_kanji` counts only when exactly "off", "before" or "alongside", else `scheduler.kanji_before_vocab` decides;
 * - deck_item `n:<id>` follows `merged_into_id` (SYNC.md 8.5).
 * - pending entries (AND-KEEPGOING-FIX): live `e:<seq>` items of enabled decks with no live note for the entry, bounded
 *   per deck by the build's new budget (the builder never draws more from one deck); the builder decides which are
 *   drawn (`toMaterialize`), [materializer] creates their notes and cards through the op path, and the queue is built
 *   again, so every caller (review, Today counts, widget, deck sessions) sees the same new cards.
 *
 * [headwordOf] supplies a dictionary headword for a vocab note whose `expression` field is empty.
 */
class LocalQueueLoader(
    private val db: AppDatabase,
    private val clock: () -> Long,
    private val smartDecks: SmartDeckResolver = SmartDeckResolver { _, _, _ -> emptyList() },
    private val headwordOf: (Long) -> String? = { null },
    /** Blocks until the startup replay (SYNC.md 10.5) has committed; no-op by default (T304b: SyncBootstrap.ready). */
    private val awaitReady: () -> Unit = {},
    /** AND-PERF: conflates concurrent builds; Koin shares one gate between every loader of the app database. */
    private val gate: LoadGate = LoadGate(),
    /** Creates notes and default cards for drawn pending entries (origin `deck`); null builds without materializing. */
    private val materializer: EntryMaterializer? = null,
) {
    private val q get() = db.localQueueQueries

    private fun settings(): QueueSettings =
        QueueSettings.from(q.schedulerSettings().executeAsList().associate { it.key to it.value_ })

    /**
     * Builds the queue now ([keepGoing]: C-26, one visit to the review screen only). [deckFilter] limits candidates to one deck; [newShownThisSession] is the UI's counter.
     * Concurrent calls with the same arguments share one build that started after they were made ([LoadGate]).
     */
    fun load(newShownThisSession: Int = 0, deckFilter: String? = null, keepGoing: Boolean = false): LoadedQueue {
        awaitReady()
        return gate.run(LoadKey(newShownThisSession, deckFilter, keepGoing)) {
            var loaded = build(newShownThisSession, deckFilter, keepGoing)
            // A drawn entry needs its note and cards first (ReviewQueue.toMaterialize), then a rebuild draws those cards.
            // The second build normally has nothing left to materialize; the bound only guards against a materializer
            // that keeps creating without the entries leaving the pending set.
            repeat(MAX_MATERIALIZE_ROUNDS) {
                val todo = loaded.queue.toMaterialize
                val m = materializer
                if (todo.isEmpty() || m == null) return@run loaded
                // a failed write costs this build its new entries, never the queue (Today, widget, review keep working)
                if (runCatching { m.materialize(todo) }.getOrDefault(0) == 0) return@run loaded
                loaded = build(newShownThisSession, deckFilter, keepGoing)
            }
            loaded
        }
    }

    private data class LoadKey(val newShownThisSession: Int, val deckFilter: String?, val keepGoing: Boolean)

    private fun build(newShownThisSession: Int, deckFilter: String?, keepGoing: Boolean): LoadedQueue {
        val now = clock()
        val settings = settings()
        val day = studyDayOf(now, settings.timezone, settings.dayRolloverHour).day
        val liveDecks = q.liveDecks().executeAsList()
        val decks = loadDecks(liveDecks, settings)

        val rows = q.queueCandidates(day.endMs, Reserved.REPLAY_REFUSED_ID).executeAsList()
        val todayRows = q.todayReviews(day.startMs, now).executeAsList()
        val memberships = memberships(decks, liveDecks.filter { it.type == "smart" }.map { it.id }, rows.associate { it.id to (it.note_id to it.created_at) }, now, day)
        val kindOrders = kindOrders()
        val parseKanji = settings.decklessAddKanji != AddKanjiMode.OFF || decks.any { it.addKanji != AddKanjiMode.OFF }

        var candidates = rows.map { r ->
            val type = NoteBaseType.entries.first { it.wire == r.type }
            QueueCandidate(
                cardId = r.id,
                noteId = r.note_id,
                kind = r.kind,
                kindOrder = kindOrders[r.note_type_id]?.indexOf(r.kind)?.takeIf { it >= 0 } ?: 0,
                noteBaseType = type,
                noteCreatedAt = r.created_at,
                state = CardState.entries.first { it.wire == r.state },
                dueAt = r.due_at,
                suspended = r.suspended,
                buriedUntil = r.buried_until,
                wordOverride = r.override_status?.let { w -> WordStatus.entries.first { it.wire == w } },
                memberships = memberships[r.note_id].orEmpty(),
                kanjiLiteral = r.kanji_literal,
                expressionKanji = if (parseKanji && type == NoteBaseType.VOCAB) kanjiOfExpression(r.fields, r.entry_seq) else emptyList(),
            )
        }
        if (deckFilter != null) candidates = candidates.filter { c -> c.memberships.any { it.deckId == deckFilter } }

        val today = todayRows.map { r ->
            val type = ReviewType.entries.first { it.wire == r.review_type }
            TodayReview(
                cardId = r.card_id,
                noteId = r.note_id,
                reviewType = type,
                introducedNew = r.introduced_new == true,
                countsAsReview = type == ReviewType.SCHEDULED,
                deckIds = memberships[r.note_id].orEmpty().map(DeckMembership::deckId),
            )
        }
        val pending = pendingEntries(decks, deckFilter, settings)
        val input = QueueInput(
            now = now,
            day = day,
            limits = QueueLimits(
                newPerDay = settings.newPerDay,
                reviewsPerDay = settings.reviewsPerDay,
                newPerSession = settings.newPerSession,
                newShownThisSession = newShownThisSession,
                burySiblings = settings.burySiblings,
                learnAheadMs = LEARN_AHEAD_MS,
                keepGoing = keepGoing,
            ),
            decks = decks,
            candidates = candidates,
            today = today,
            pendingEntries = pending,
            decklessPriority = settings.minedPriority,
            decklessAddKanji = settings.decklessAddKanji,
        )
        return LoadedQueue(input, DefaultQueueBuilder.build(input))
    }

    /** Due / new / learning counts of the current queue. */
    fun counts(): QueueCounts = load().counts

    /**
     * When the next card becomes due: now if the queue is not empty, else the earliest later instant a started,
     * queueable card is due (a bury's end counts); null if there is none.
     */
    fun nextDueInstant(): Long? = nextDueInstant(load())

    /** [nextDueInstant] for a queue the caller already loaded (no second build). */
    fun nextDueInstant(loaded: LoadedQueue): Long? {
        if (loaded.queue.items.isNotEmpty()) return loaded.input.now
        return q.nextDueAfter(Reserved.REPLAY_REFUSED_ID, loaded.input.now).executeAsOne().MIN
    }

    /** Started, queueable cards due in each of the next 24 hours from now (hour 0 includes everything overdue). */
    fun forecast24h(): List<Int> {
        awaitReady()
        val now = clock()
        val out = IntArray(24)
        for (b in q.forecastBuckets(now, Reserved.REPLAY_REFUSED_ID, now + 24 * HOUR_MS).executeAsList()) {
            val i = b.bucket.toInt()
            if (i in 0..23) out[i] += b.n.toInt()
        }
        return out.toList()
    }

    private fun loadDecks(rows: List<kotobaya.db.app.LiveDecks>, settings: QueueSettings): List<QueueDeck> =
        rows.map { r ->
            val explicit = (jsonObject(r.settings)["add_kanji"] as? JsonString)?.value
                ?.let { s -> AddKanjiMode.entries.firstOrNull { it.wire == s } }
            QueueDeck(
                id = r.id,
                priority = r.priority.toInt(),
                enabled = r.enabled,
                newPerDay = r.new_cards_per_day?.toInt(),
                reviewsPerDay = r.reviews_per_day?.toInt(),
                addKanji = explicit ?: settings.decklessAddKanji,
            )
        }

    /**
     * The pending entries of the enabled decks (of [deckFilter] only, if set), at most the build's largest new budget
     * per deck: the builder takes at most that many new cards and entries from any one deck (rule 4 budget; the
     * normal build's `moreAvailable` runs rule 6 with the keep-going batch), so later entries could never be drawn.
     */
    private fun pendingEntries(decks: List<QueueDeck>, deckFilter: String?, settings: QueueSettings): List<PendingEntry> {
        val limit = maxOf(minOf(settings.newPerDay, settings.newPerSession), QueueLimits.DEFAULT_KEEP_GOING_NEW_BATCH, 1).toLong()
        return decks.filter { it.enabled && (deckFilter == null || it.id == deckFilter) }.flatMap { deck ->
            q.pendingDeckEntries(deck.id, limit).executeAsList().map { r ->
                PendingEntry(
                    deckId = deck.id,
                    entrySeq = checkNotNull(r.entry_seq),
                    position = r.position,
                    itemKey = r.item_key,
                    wordOverride = r.override_status?.let { w -> WordStatus.entries.firstOrNull { it.wire == w } },
                )
            }
        }
    }

    /** Index of each card kind in its note type's templates: built-in order for built-in types, else alphabetical (as the server). */
    private fun kindOrders(): Map<String, List<String>> {
        val builtIn = BuiltInNoteTypes.ALL.associate { it.id to it.info.templates.map { t -> t.kind } }
        val custom = q.noteTypeTemplates().executeAsList().associate { r ->
            r.id to (builtIn[r.id] ?: jsonObject(r.templates).keys.sorted())
        }
        return builtIn + custom
    }

    /** Deck memberships by note id, to live decks only. [cardNote]: candidate card id to (note id, note created_at). */
    private fun memberships(
        decks: List<QueueDeck>,
        smartDeckIds: List<String>,
        cardNote: Map<String, Pair<String, Long>>,
        nowMs: Long,
        day: StudyDay,
    ): Map<String, List<DeckMembership>> {
        if (decks.isEmpty()) return emptyMap()
        val liveDeckIds = decks.mapTo(HashSet()) { it.id }
        val out = HashMap<String, MutableMap<String, DeckMembership>>()
        fun add(noteId: String, m: DeckMembership) {
            val byDeck = out.getOrPut(noteId) { LinkedHashMap() }
            val old = byDeck[m.deckId]
            if (old == null || compareValuesBy(m, old, DeckMembership::position, DeckMembership::itemKey) < 0) byDeck[m.deckId] = m
        }
        for (r in q.deckItemMemberships().executeAsList()) {
            if (r.deck_id in liveDeckIds) add(r.note_id, DeckMembership(r.deck_id, r.position, r.item_key))
        }
        for (deckId in smartDeckIds) for (cardId in smartDecks.cardIds(deckId, nowMs, day)) {
            cardNote[cardId]?.let { (noteId, createdAt) -> add(noteId, DeckMembership(deckId, createdAt, noteId)) }
        }
        return out.mapValues { (_, byDeck) -> byDeck.values.toList() }
    }

    private fun kanjiOfExpression(fieldsJson: String, entrySeq: Long?): List<String> {
        val expression = (jsonObject(fieldsJson)["expression"] as? JsonString)?.value?.takeIf { it.isNotEmpty() }
            ?: entrySeq?.let(headwordOf)
            ?: return emptyList()
        return DefaultCardGenerator.kanjiOf(expression.replace(HTML_TAG, ""))
    }

    private fun jsonObject(text: String): Map<String, JsonValue> =
        (DefaultCanonicalJson.decode(text).value as? JsonObject)?.members.orEmpty()

    companion object {
        /** `QueueLimits.learnAheadMs` default of the contract: 20 minutes. */
        const val LEARN_AHEAD_MS: Long = 1_200_000
        private const val HOUR_MS = 3_600_000L
        private const val MAX_MATERIALIZE_ROUNDS = 3
        private val HTML_TAG = Regex("<[^>]*>")
    }
}

/**
 * AND-KEEPGOING-FIX: creates the note (origin `deck`, the entry's `entry_seq`) and default cards of each drawn pending
 * entry through the device's op path (SYNC.md 4), skipping an entry that has a live note by then. Returns how many
 * notes it created (0: nothing to do, or minting was refused).
 */
fun interface EntryMaterializer {
    fun materialize(entries: List<PendingEntry>): Int
}
