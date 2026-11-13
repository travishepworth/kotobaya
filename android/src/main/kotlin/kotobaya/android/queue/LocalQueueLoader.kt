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
data class QueueCounts(val due: Int, val new: Int, val learning: Int)

/** A built queue with the exact input that produced it. */
class LoadedQueue internal constructor(
    internal val input: QueueInput,
    val queue: ReviewQueue,
) {
    val counts: QueueCounts
        get() = QueueCounts(
            due = queue.items.count { it.kind == QueueItemKind.REVIEW },
            new = queue.items.count { it.kind == QueueItemKind.NEW },
            learning = queue.items.count { it.kind == QueueItemKind.LEARNING },
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
 * Entry items without a live note are not passed as pending entries (the loader emits no ops).
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
) {
    private val q get() = db.localQueueQueries

    private fun settings(): QueueSettings =
        QueueSettings.from(q.schedulerSettings().executeAsList().associate { it.key to it.value_ })

    /** Builds the queue now. [deckFilter] limits candidates to one deck; [newShownThisSession] is the UI's counter. */
    fun load(newShownThisSession: Int = 0, deckFilter: String? = null): LoadedQueue {
        awaitReady()
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
            ),
            decks = decks,
            candidates = candidates,
            today = today,
            pendingEntries = emptyList(),
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
    fun nextDueInstant(): Long? {
        val loaded = load()
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
        private val HTML_TAG = Regex("<[^>]*>")
    }
}
