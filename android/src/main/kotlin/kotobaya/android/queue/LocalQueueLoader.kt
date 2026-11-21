package kotobaya.android.queue

import kotobaya.render.FieldPlainText
import kotobaya.render.PlainTextMode
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.cards.DefaultCardGenerator
import kotobaya.cards.DefaultKanjiRequirement
import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.Reserved
import kotobaya.contracts.WordStatus
import kotobaya.contracts.cards.CardKinds
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
import kotobaya.contracts.queue.LoopResult
import kotobaya.contracts.queue.MissingKanji
import kotobaya.contracts.queue.LoopState
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.queue.StudyDay
import kotobaya.contracts.queue.TodayReview
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.queue.DefaultReviewLoop
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTable

/**
 * The frozen pre-ADR-037 counts (`LoopState.legacy`), kept only for the legacy Compose screens until SHELL-Z1 deletes
 * them. Nothing new reads them: widget, notification and the shell route use [LoopState].
 */
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
    val result: LoopResult,
    /**
     * RC-AND: creating the notes of drawn deck entries threw, so `queue.toMaterialize`
     * holds new cards that [state] counts but `queue.items` does not; null when nothing failed.
     */
    val materializeFailure: Throwable? = null,
) {
    /** The loop evaluated over [input] (DeckRepository's per-deck counts). */
    internal constructor(input: QueueInput) : this(input, DefaultReviewLoop.evaluate(input))

    /** The queue the loop serves (N, or K under keep going). */
    val queue: ReviewQueue get() = result.queue

    /** The loop's phase and numbers (ADR-037). */
    val state: LoopState get() = result.state

    /** Legacy view of [state] for the old Compose screens (frozen meaning, see [QueueCounts]). */
    val counts: QueueCounts
        get() = state.legacy.let { QueueCounts(due = it.review, new = it.new, learning = it.learning, moreAvailable = it.moreAvailable) }

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
 * - ADR-040 / ADR-042: `add_kanji` and `scheduler.kanji_before_vocab` are not read (the builder puts kanji first for
 *   every source); the loader passes each vocab note's and pending entry's required and missing kanji ([KanjiSource] +
 *   core `KanjiRequirement`), the companions of a scoped build, and materializes `kanjiToMaterialize` (step A) before
 *   the entries (step B) through [materializer];
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
    /** PERF-LF: loads the headwords [headwordOf] will be asked for, in one batch per build (a process cache: nothing after the first). */
    private val prefetchHeadwords: (Collection<Long>) -> Unit = {},
    /** Blocks until the startup replay (SYNC.md 10.5) has committed; no-op by default (T304b: SyncBootstrap.ready). */
    private val awaitReady: () -> Unit = {},
    /** AND-PERF: conflates concurrent builds; Koin shares one gate between every loader of the app database. */
    private val gate: LoadGate = LoadGate(),
    /** Creates notes and default cards for drawn pending entries (origin `deck`); null builds without materializing. */
    private val materializer: EntryMaterializer? = null,
    /** ADR-042: dictionary facts for `KanjiRequirement` (form tags, `uk`, kanji grade / rank); none = every word requires nothing. */
    private val kanji: KanjiSource = KanjiSource.NONE,
    /** PERF-LF: the replica reads kept between builds (production: one per app database); null reads everything per build. */
    private val cache: QueueReadCache? = null,
) {
    private val q get() = db.localQueueQueries

    private fun settings(): QueueSettings =
        QueueSettings.from(q.schedulerSettings().executeAsList().associate { it.key to it.value_ })

    /**
     * Evaluates the review loop now (ADR-037; [keepGoing]: C-26, per device). [deckFilter] limits candidates to one deck.
     * Concurrent calls with the same arguments share one build that started after they were made ([LoadGate]).
     */
    fun load(
        deckFilter: String? = null,
        keepGoing: Boolean = false,
        keepGoingNewBatch: Int = QueueLimits.DEFAULT_KEEP_GOING_NEW_BATCH,
        keepGoingNewTarget: Int? = null,
    ): LoadedQueue {
        awaitReady()
        return gate.run(LoadKey(deckFilter, keepGoing, keepGoingNewBatch, keepGoingNewTarget)) {
            var loaded = build(deckFilter, keepGoing, keepGoingNewBatch, keepGoingNewTarget)
            // C-27 rounds (as the server): a drawn entry needs its note and cards first (ReviewQueue.toMaterialize), then
            // a rebuild draws those cards in its place. Another round only if the last one's notes reached `items`, so a
            // mismatch between what was drawn and what shows can never create notes that are not shown.
            var rounds = 0
            val m = materializer
            while (m != null && (loaded.queue.toMaterialize.isNotEmpty() || loaded.queue.kanjiToMaterialize.isNotEmpty()) && rounds < MAX_MATERIALIZE_ROUNDS) {
                // a failed write costs this build its new entries, never the queue (Today, widget keep working); it is
                // recorded so a review session does not end as "done" while those cards are counted (RC-AND)
                val created = try {
                    m.materialize(loaded.queue.kanjiToMaterialize, loaded.queue.toMaterialize)
                } catch (e: Exception) {
                    kotobaya.android.report.ScreenLog.loadFailed("LocalQueueLoader.materialize", e)
                    return@run LoadedQueue(loaded.input, loaded.result, MaterializeFailed(e))
                }
                // nothing created is not a failure: the entries may have notes by now (another build, a pull)
                if (created.isEmpty()) break
                loaded = build(deckFilter, keepGoing, keepGoingNewBatch, keepGoingNewTarget)
                rounds++
                val noteOf = loaded.input.candidates.associate { it.cardId to it.noteId }
                if (loaded.queue.items.none { noteOf[it.cardId] in created }) break
            }
            loaded
        }
    }

    private data class LoadKey(val deckFilter: String?, val keepGoing: Boolean, val keepGoingNewBatch: Int, val keepGoingNewTarget: Int?)

    private fun build(deckFilter: String?, keepGoing: Boolean, keepGoingNewBatch: Int, keepGoingNewTarget: Int?): LoadedQueue {
        val now = clock()
        val settings = settings()
        val day = studyDayOf(now, settings.timezone, settings.dayRolloverHour).day
        val liveDecks = q.liveDecks().executeAsList()
        val decks = loadDecks(liveDecks, settings)

        val rows = cache?.candidates(q)?.filter { it.queueable(day.endMs) }
            ?: q.queueCandidates(day.endMs, Reserved.REPLAY_REFUSED_ID, CandidateRow::of).executeAsList()
        val todayRows = q.todayReviews(day.startMs, now).executeAsList()
        val memberships = memberships(decks, liveDecks.filter { it.type == "smart" }.map { it.id }, rows.associate { it.id to (it.note_id to it.created_at) }, now, day)
        val kindOrders = kindOrders()

        val all = rows.map { r ->
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
                expressionKanji = emptyList(),
            )
        }
        fun inScope(c: QueueCandidate) = deckFilter == null || c.memberships.any { it.deckId == deckFilter }
        var candidates = all.filter(::inScope)

        // ADR-040 / ADR-042 (contracts/core README "C-27"): the kanji each vocab note and pending entry requires, and the
        // required ones with no kanji note, evaluated by core KanjiRequirement over the dictionary
        val resolver = WordKanjiResolver(settings, kanjiNoteLiterals(), kanji, materializer)
        val fieldsOf = rows.asSequence().filter { it.type == NoteBaseType.VOCAB.wire }.associate { it.note_id to (it.entry_seq to it.fields) }
        // the notes whose expression comes from the dictionary (fields `{}`: jpdb imports, deck notes) or may (blank field)
        prefetchHeadwords(fieldsOf.values.mapNotNullTo(HashSet()) { (seq, fields) -> seq?.takeIf { !fields.contains("\"expression\":\"") || fields.contains("\"expression\":\"\"") } })
        for (c in candidates) if (c.noteBaseType == NoteBaseType.VOCAB && !resolver.has(c.noteId)) {
            val (seq, fields) = fieldsOf.getValue(c.noteId)
            resolver.evaluate(c.noteId, seq, expressionOf(fields, seq))
        }
        val (pendingBase, pendingExpression) = pendingEntries(decks, deckFilter, settings, keepGoingNewBatch)
        for (e in pendingBase) resolver.evaluate("e:${e.entrySeq}", e.entrySeq, pendingExpression.getValue(e.entrySeq))
        resolver.finish()
        candidates = candidates.map { c ->
            if (c.noteBaseType != NoteBaseType.VOCAB) c else resolver.of(c.noteId).let { w ->
                c.copy(expressionKanji = w.expressionKanji, requiredKanji = w.required, missingKanji = w.missing)
            }
        }
        val pending = pendingBase.map { e -> resolver.of("e:${e.entrySeq}").let { w -> e.copy(expressionKanji = w.expressionKanji, requiredKanji = w.required, missingKanji = w.missing) } }
        if (deckFilter != null) {
            // a scoped build still needs the kanji cards of its words (companions: new only through a unit or backfill)
            val required = HashSet<String>()
            candidates.forEach { c -> c.requiredKanji?.let(required::addAll) }
            pending.forEach { e -> e.requiredKanji?.let(required::addAll) }
            val inDeck = candidates.mapTo(HashSet()) { it.cardId }
            candidates = candidates + all.filter { c -> c.kanjiLiteral in required && c.cardId !in inDeck }.map { it.copy(companion = true) }
        }

        val today = todayRows.map { r ->
            val type = ReviewType.entries.first { it.wire == r.review_type }
            TodayReview(
                cardId = r.card_id,
                noteId = r.note_id,
                reviewType = type,
                introducedNew = r.introduced_new == true,
                countsAsReview = type == ReviewType.SCHEDULED,
                deckIds = memberships[r.note_id].orEmpty().map(DeckMembership::deckId),
                reviewedAt = r.reviewed_at,
            )
        }
        val input = QueueInput(
            now = now,
            day = day,
            limits = QueueLimits(
                newPerDay = settings.newPerDay,
                reviewsPerDay = settings.reviewsPerDay,
                newPerSession = 0, // ADR-037: deprecated, ignored
                newShownThisSession = 0,
                burySiblings = settings.burySiblings,
                learnAheadMs = LEARN_AHEAD_MS,
                keepGoing = keepGoing,
                keepGoingNewBatch = keepGoingNewBatch,
                keepGoingNewTarget = if (keepGoing) keepGoingNewTarget else null,
                reviewsUnlimited = settings.reviewsUnlimited,
            ),
            decks = decks,
            candidates = candidates,
            today = today,
            pendingEntries = pending,
            decklessPriority = settings.minedPriority,
            decklessAddKanji = AddKanjiMode.OFF, // ADR-040: not read; kanji first applies to every source
            readingEnabled = settings.readingEnabled,
        )
        return LoadedQueue(input)
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
        return q.nextDueAfter(Reserved.REPLAY_REFUSED_ID, if (loaded.input.readingEnabled) 1L else 0L, loaded.input.now).executeAsOne().MIN
    }

    /** Started, queueable cards due in each of the next 24 hours from now (hour 0 includes everything overdue). */
    fun forecast24h(): List<Int> {
        awaitReady()
        val now = clock()
        val out = IntArray(24)
        val readingEnabled = if (settings().readingEnabled) 1L else 0L
        for (b in q.forecastBuckets(now, Reserved.REPLAY_REFUSED_ID, readingEnabled, now + 24 * HOUR_MS).executeAsList()) {
            val i = b.bucket.toInt()
            if (i in 0..23) out[i] += b.n.toInt()
        }
        return out.toList()
    }

    private fun loadDecks(rows: List<kotobaya.db.app.LiveDecks>, settings: QueueSettings): List<QueueDeck> =
        rows.map { r ->
            QueueDeck(
                id = r.id,
                priority = r.priority.toInt(),
                enabled = r.enabled,
                newPerDay = r.new_cards_per_day?.toInt(),
                reviewsPerDay = r.reviews_per_day?.toInt(),
                addKanji = AddKanjiMode.OFF, // ADR-040: not read
            )
        }

    /**
     * C-27 pending entries of the enabled decks (of [deckFilter] only, if set): the query's rules (no live or
     * user-deleted note, override filter) plus the [materializer]'s plan (dictionary entry installed, at least one vocab
     * kind; `expressionKanji` and `newKanjiCards` for the builder's add_kanji group). None while the materializer is
     * not [EntryMaterializer.ready] (no dictionary, or a pull in progress: the notes may still be on their way). Per deck
     * the first `2 * B + 16` in `(position, item_key)` order, `B` = `max(1, batch)` (ADR-037); later ones are reached by a later build.
     */
    private fun pendingEntries(
        decks: List<QueueDeck>,
        deckFilter: String?,
        settings: QueueSettings,
        keepGoingNewBatch: Int,
    ): Pair<List<PendingEntry>, Map<Long, String>> {
        val m = materializer ?: return emptyList<PendingEntry>() to emptyMap()
        if (!runCatching { m.ready() }.getOrDefault(false)) return emptyList<PendingEntry>() to emptyMap()
        val batch = maxOf(1, keepGoingNewBatch)
        val window = 2 * batch + 16 // ADR-037: B = max(1, batch) bounds both builds of the loop
        val page = maxOf(window * 2, MIN_PENDING_PAGE).toLong()
        val expressions = HashMap<Long, String>()
        val entries = decks.filter { it.enabled && (deckFilter == null || it.id == deckFilter) }.flatMap { deck ->
            val out = ArrayList<PendingEntry>()
            var offset = 0L
            while (out.size < window) {
                val rows = pendingDeckEntries(deck.id, page, offset)
                val plans = runCatching { m.plan(rows.map { EntryRef(deck.id, checkNotNull(it.entry_seq)) }, settings.burySiblings) }
                    .getOrDefault(emptyMap())
                for (r in rows) {
                    val ref = EntryRef(deck.id, checkNotNull(r.entry_seq))
                    val plan = plans[ref] ?: continue
                    expressions[ref.entrySeq] = plan.expression
                    out += PendingEntry(
                        deckId = deck.id,
                        entrySeq = ref.entrySeq,
                        position = r.position,
                        itemKey = r.item_key,
                        wordOverride = r.override_status?.let { w -> WordStatus.entries.firstOrNull { it.wire == w } },
                        // expressionKanji, requiredKanji and missingKanji are filled in by the caller (WordKanjiResolver)
                    )
                    if (out.size == window) break
                }
                if (rows.size < page) break
                offset += page
            }
            out
        }
        return entries to expressions
    }

    // PERF-LF: the reads below depend only on the tables named; with a cache they are read again only after one changed

    private fun kanjiNoteLiterals(): Set<String> =
        memo("kanjiNoteLiterals", LearningTable.NOTE) { q.kanjiNoteLiterals().executeAsList().mapNotNullTo(HashSet()) { it } }

    private fun deckItemMemberships(): List<kotobaya.db.app.DeckItemMemberships> =
        memo("deckItemMemberships", LearningTable.DECK, LearningTable.DECK_ITEM, LearningTable.NOTE) { q.deckItemMemberships().executeAsList() }

    private fun pendingDeckEntries(deckId: String, page: Long, offset: Long): List<kotobaya.db.app.PendingDeckEntries> =
        memo("pendingDeckEntries|$deckId|$page|$offset", LearningTable.DECK_ITEM, LearningTable.NOTE, LearningTable.WORD_STATUS) {
            q.pendingDeckEntries(deckId, page, offset).executeAsList()
        }

    private fun <T> memo(name: String, vararg tables: LearningTable, load: () -> T): T =
        cache?.let { c -> c.memo(name, c.version(*tables), load = load) } ?: load()

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
        for (r in deckItemMemberships()) {
            if (r.deck_id in liveDeckIds) add(r.note_id, DeckMembership(r.deck_id, r.position, r.item_key))
        }
        for (deckId in smartDeckIds) for (cardId in smartDecks.cardIds(deckId, nowMs, day)) {
            cardNote[cardId]?.let { (noteId, createdAt) -> add(noteId, DeckMembership(deckId, createdAt, noteId)) }
        }
        return out.mapValues { (_, byDeck) -> byDeck.values.toList() }
    }

    /** The note's displayed `expression` as plain text: its `expression` field unless blank, else the entry's headword. */
    private fun expressionOf(fieldsJson: String, entrySeq: Long?): String {
        val field = if (fieldsJson.length <= 2) null else (jsonObject(fieldsJson)["expression"] as? JsonString)?.value
        return WordText.expression(field, entrySeq?.let(headwordOf))
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
    }
}

/** RC-AND: drawn deck entries could not be given notes and cards; the review session shows Retry instead of "done". */
class MaterializeFailed(cause: Throwable) : RuntimeException("deck entries not materialized", cause)

/** A deck entry `e:<seq>` of one deck. */
data class EntryRef(val deckId: String, val entrySeq: Long)

/** C-27: what the loader needs to know of an entry before it is drawn: its displayed form (ADR-042 reads the kanji from it). */
data class EntryPlan(val expression: String, val expressionKanji: List<String> = emptyList())

/**
 * AND-KEEPGOING-FIX, C-27, ADR-040 (contracts/core README "C-27"): plans and creates the kanji notes and the deck
 * entries' notes and cards of a served build through the device's op path (production:
 * [kotobaya.android.notes.NoteCreator] over `DeckEntryPlan`, so both agree).
 */
interface EntryMaterializer {
    /**
     * False while creating notes for entries could duplicate notes that already exist elsewhere and have not arrived
     * yet (a pull in progress or interrupted), or while nothing can be created (no dictionary): the loader then passes
     * no pending entries at all, so none uses up the new budget.
     */
    fun ready(): Boolean = true

    /** The plan of each entry that may be passed (dictionary entry installed, at least one vocab kind); others are absent. */
    fun plan(entries: List<EntryRef>, burySiblings: Boolean): Map<EntryRef, EntryPlan>

    /** The card kinds step A creates for each of [literals] (a literal with none is never minted); default: one card each. */
    fun kanjiKinds(literals: Collection<String>): Map<String, List<String>> = literals.associateWith { listOf("meaning") }

    /**
     * Step A (a kanji note for each of [kanji] with neither a live nor a user-deleted one) then step B (the notes and
     * cards of the drawn [entries]) in one transaction; returns the ids of the notes created (empty: none, or refused).
     */
    fun materialize(kanji: List<String>, entries: List<PendingEntry>): Set<String>
}

/**
 * ADR-040 / ADR-042 inputs of one build: evaluates `KanjiRequirement` per vocab note and pending entry, then (after
 * [finish], one batched card-kind lookup) says which required kanji have no kanji note and how many cards minting gives.
 */
private class WordKanjiResolver(
    private val settings: QueueSettings,
    /** literals of the live kanji notes and of those the user deleted (never re-created). */
    private val blocked: Set<String>,
    private val source: KanjiSource,
    private val materializer: EntryMaterializer?,
) {
    class Kanji(val expressionKanji: List<String>, val required: List<String>, val missing: List<MissingKanji>)

    private class Word(val seq: Long?, val expression: String)

    private val words = LinkedHashMap<String, Word>()
    private val result = HashMap<String, Kanji>()

    fun has(key: String): Boolean = key in words

    /** Records a word; nothing is read until [finish], which batches the dictionary reads of the whole build. */
    fun evaluate(key: String, seq: Long?, expression: String) {
        words.putIfAbsent(key, Word(seq, expression))
    }

    fun finish() {
        val exempt = settings.kanjiExemptions
        val all = words.mapValues { (_, w) -> DefaultCardGenerator.kanjiOf(w.expression) }
        // one pass over the dictionary for every entry and (only when exemptions are on) every kanji of the build
        source.prefetch(words.values.mapNotNull { it.seq }, if (exempt) all.values.flatten() else emptyList())
        val required = LinkedHashMap<String, Pair<List<String>, List<String>>>()
        for ((key, w) in words) {
            val kanji = all.getValue(key)
            val req = when {
                kanji.isEmpty() -> emptyList()
                !exempt -> kanji // setting off: every kanji, no facts needed
                else -> DefaultKanjiRequirement.evaluate(source.form(w.seq, w.expression), kanji.map(source::facts), true).learnableKanji
            }
            required[key] = kanji to req
        }
        val lits = required.values.flatMapTo(LinkedHashSet()) { it.second }.filter { it !in blocked }
        val kinds = if (lits.isEmpty()) emptyMap() else runCatching { materializer?.kanjiKinds(lits) }.getOrNull() ?: lits.associateWith { listOf("meaning") }
        for ((key, p) in required) {
            val (all, req) = p
            val missing = req.mapNotNull { l ->
                if (l in blocked) return@mapNotNull null
                val n = kinds[l]?.size ?: 1
                if (n < 1) null else MissingKanji(l, if (settings.burySiblings) 1 else n)
            }
            result[key] = Kanji(all, req, missing)
        }
    }

    fun of(key: String): Kanji = result[key] ?: Kanji(emptyList(), emptyList(), emptyList())
}

/**
 * When a paired device may materialize deck entries (SYNC.md 8.1 "When a device materializes", SHELL.md 4.3, C-31):
 * after its first complete pull (`last_sync_at` set by a cycle that pulled until `has_more` was false) and with no
 * rebuild pending. Nothing else: not whether a cycle is running, has failed or how often, nor being online (L-9). An
 * unpaired device has nothing to pull and may always.
 */
fun materializeAllowed(paired: Boolean, lastSyncAt: Long?, rebuildPending: Boolean): Boolean =
    !paired || (lastSyncAt != null && !rebuildPending)
