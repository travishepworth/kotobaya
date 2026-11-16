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
    /**
     * RC-AND: creating the notes of drawn deck entries threw, so `queue.toMaterialize`
     * holds new cards that [counts] includes but `queue.items` does not; null when nothing failed.
     */
    val materializeFailure: Throwable? = null,
) {
    val counts: QueueCounts
        get() = QueueCounts(
            due = queue.items.count { it.kind == QueueItemKind.REVIEW },
            // entries still to materialize (no materializer, or it refused) are new cards of this queue too
            new = queue.items.count { it.kind == QueueItemKind.NEW } + queue.toMaterialize.size,
            // ADR-033: learning cards due later today are still to do today (as the server's `counts.learning`)
            learning = queue.items.count { it.kind == QueueItemKind.LEARNING } + laterLearning(),
            moreAvailable = queue.moreAvailable,
        )

    /**
     * Learning and relearning candidates not in the queue but due before the study day ends, eligible as core's rule 1
     * has it for learning cards (not suspended, not buried now, no excluding word override): they make
     * `nextLearningDueAt` and show once due.
     */
    private fun laterLearning(): Int {
        val inQueue = queue.items.mapTo(HashSet()) { it.cardId }
        return input.candidates.count { c ->
            val due = c.dueAt
            c.cardId !in inQueue && (c.state == CardState.LEARNING || c.state == CardState.RELEARNING) && !c.suspended &&
                (c.buriedUntil?.let { it <= input.now } ?: true) && c.wordOverride !in LATER_EXCLUDED &&
                due != null && due < input.day.endMs
        }
    }

    /** The first [size] items: what a review session starts with. */
    fun sessionPrefix(size: Int): List<QueueItem> = queue.items.take(size)

    private companion object {
        /** Core rule 1's excluded overrides (QueueBuilder `excludedOverrides`). */
        val LATER_EXCLUDED = setOf(WordStatus.NEVER_FORGET, WordStatus.BLACKLISTED, WordStatus.IGNORED)
    }
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
            // C-27 rounds (as the server): a drawn entry needs its note and cards first (ReviewQueue.toMaterialize), then
            // a rebuild draws those cards in its place. Another round only if the last one's notes reached `items`, so a
            // mismatch between what was drawn and what shows can never create notes that are not shown.
            var rounds = 0
            val m = materializer
            while (m != null && loaded.queue.toMaterialize.isNotEmpty() && rounds < MAX_MATERIALIZE_ROUNDS) {
                // a failed write costs this build its new entries, never the queue (Today, widget keep working); it is
                // recorded so a review session does not end as "done" while those cards are counted (RC-AND)
                val created = try {
                    m.materialize(loaded.queue.toMaterialize)
                } catch (e: Exception) {
                    kotobaya.android.vm.ScreenLog.loadFailed("LocalQueueLoader.materialize", e)
                    return@run LoadedQueue(loaded.input, loaded.queue, MaterializeFailed(e))
                }
                // nothing created is not a failure: the entries may have notes by now (another build, a pull)
                if (created.isEmpty()) break
                loaded = build(newShownThisSession, deckFilter, keepGoing)
                rounds++
                val noteOf = loaded.input.candidates.associate { it.cardId to it.noteId }
                if (loaded.queue.items.none { noteOf[it.cardId] in created }) break
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
        val pending = pendingEntries(decks, deckFilter, settings, keepGoing)
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
                reviewsUnlimited = settings.reviewsUnlimited,
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
     * C-27 pending entries of the enabled decks (of [deckFilter] only, if set): the query's rules (no live or
     * user-deleted note, override filter) plus the [materializer]'s plan (dictionary entry installed, at least one vocab
     * kind; `expressionKanji` and `newKanjiCards` for the builder's add_kanji group). None while the materializer is
     * not [EntryMaterializer.ready] (no dictionary, or a pull in progress: the notes may still be on their way). Per deck
     * the first `2 * B + 16` in `(position, item_key)` order, `B` = the keep-going batch when keeping going, else the
     * larger of it and `min(newPerDay, newPerSession)` (`moreAvailable` probes with the batch); later ones are reached by
     * a later build.
     */
    private fun pendingEntries(decks: List<QueueDeck>, deckFilter: String?, settings: QueueSettings, keepGoing: Boolean): List<PendingEntry> {
        val m = materializer ?: return emptyList()
        if (!runCatching { m.ready() }.getOrDefault(false)) return emptyList()
        val batch = maxOf(1, QueueLimits.DEFAULT_KEEP_GOING_NEW_BATCH)
        val budget = if (keepGoing) batch else maxOf(batch, minOf(settings.newPerDay, settings.newPerSession))
        val window = 2 * budget + 16
        val page = maxOf(window * 2, MIN_PENDING_PAGE).toLong()
        return decks.filter { it.enabled && (deckFilter == null || it.id == deckFilter) }.flatMap { deck ->
            val out = ArrayList<PendingEntry>()
            var offset = 0L
            while (out.size < window) {
                val rows = q.pendingDeckEntries(deck.id, page, offset).executeAsList()
                val plans = runCatching { m.plan(rows.map { EntryRef(deck.id, checkNotNull(it.entry_seq)) }, settings.burySiblings) }
                    .getOrDefault(emptyMap())
                for (r in rows) {
                    val ref = EntryRef(deck.id, checkNotNull(r.entry_seq))
                    val plan = plans[ref] ?: continue
                    out += PendingEntry(
                        deckId = deck.id,
                        entrySeq = ref.entrySeq,
                        position = r.position,
                        itemKey = r.item_key,
                        wordOverride = r.override_status?.let { w -> WordStatus.entries.firstOrNull { it.wire == w } },
                        expressionKanji = plan.expressionKanji,
                        newKanjiCards = plan.newKanjiCards,
                    )
                    if (out.size == window) break
                }
                if (rows.size < page) break
                offset += page
            }
            out
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
        /**
         * `QueueLimits.learnAheadMs`: 0 (ADR-033, as the server). A learning card is in the queue only once due; one due
         * later today sets `nextLearningDueAt`, for which the review screen waits.
         */
        const val LEARN_AHEAD_MS: Long = 0
        private const val HOUR_MS = 3_600_000L
        private const val MAX_MATERIALIZE_ROUNDS = 3
        private const val MIN_PENDING_PAGE = 64
        private val HTML_TAG = Regex("<[^>]*>")
    }
}

/** RC-AND: drawn deck entries could not be given notes and cards; the review session shows Retry instead of "done". */
class MaterializeFailed(cause: Throwable) : RuntimeException("deck entries not materialized", cause)

/** A deck entry `e:<seq>` of one deck. */
data class EntryRef(val deckId: String, val entrySeq: Long)

/** C-27: what the builder needs to know of an entry's add_kanji group (`PendingEntry.expressionKanji`, `newKanjiCards`). */
data class EntryPlan(val expressionKanji: List<String>, val newKanjiCards: Int)

/**
 * AND-KEEPGOING-FIX, C-27 (contracts/core README "C-27"): plans and creates deck entries' notes and cards through the
 * device's op path (production: [kotobaya.android.notes.NoteCreator] over `DeckEntryPlan`, so both agree).
 */
interface EntryMaterializer {
    /**
     * False while creating notes for entries could duplicate notes that already exist elsewhere and have not arrived
     * yet (a pull in progress or interrupted), or while nothing can be created (no dictionary): the loader then passes
     * no pending entries at all, so none uses up the new budget.
     */
    fun ready(): Boolean = true

    /** The plan of each entry that may be passed (dictionary entry installed, at least one vocab card kind); others are absent. */
    fun plan(entries: List<EntryRef>, burySiblings: Boolean): Map<EntryRef, EntryPlan>

    /** Creates the notes and cards of the drawn [entries]; returns the ids of the vocab notes created (empty: none, or refused). */
    fun materialize(entries: List<PendingEntry>): Set<String>
}

/**
 * When a paired device may materialize deck entries: after a complete pull (`last_sync_at` set by a cycle that pulled
 * until `has_more` was false), with no cycle running, no failed cycle since (a pull cut off mid-way counts as one) and
 * no rebuild pending. An unpaired device has nothing to pull and may always.
 */
fun materializeAllowed(paired: Boolean, syncRunning: Boolean, failures: Int, lastSyncAt: Long?, rebuildPending: Boolean): Boolean =
    !paired || (!syncRunning && failures == 0 && lastSyncAt != null && !rebuildPending)
