package kotobaya.review

import kotobaya.contracts.NoteBaseType
import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.queue.AddKanjiMode
import kotobaya.contracts.queue.DeckMembership
import kotobaya.contracts.queue.QueueCandidate
import kotobaya.contracts.queue.QueueDeck
import kotobaya.contracts.queue.QueueInput
import kotobaya.contracts.queue.QueueLimits
import kotobaya.contracts.queue.TodayReview
import kotobaya.cards.BuiltInNoteTypes
import kotobaya.decks.SmartDecks
import kotobaya.server.ApiException
import org.jooq.DSLContext

/** What [QueueLoader.load] hands to the core plus what the caller needs to answer counts. */
internal class LoadedQueue(val input: QueueInput, val candidates: List<QueueCandidate>)

/**
 * The platform loader of the core queue builder (QueueBuilder.kt, SCHEMA.md 6): turns the server replica into a
 * [QueueInput] and applies no queue rule itself. What it does decide is what "live" means for the inputs:
 * - decks are the live ones only, and a membership naming a tombstoned or unknown deck is dropped, so a NEW card
 *   whose only deck was deleted falls back to the deckless source;
 * - candidates are live cards of live notes (not merge losers); suspended cards and cards that cannot be queued
 *   today are left out in SQL (the builder would drop them anyway);
 * - `add_kanji` of a deck counts only when it is exactly `"off"`, `"before"` or `"alongside"`, else it falls back to
 *   `scheduler.kanji_before_vocab` (SCHEMA.md 6.2);
 * - entry items `e:<seq>` of enabled decks without a live note are **not** passed as pending entries: `GET
 *   /review/queue` emits no ops (`x-emits-ops` is empty), so it cannot create their notes.
 */
internal class QueueLoader(private val dsl: DSLContext) {
    fun load(nowMs: Long, settings: ReviewSettings, deckFilter: String? = null): LoadedQueue {
        val day = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour).day
        val decks = loadDecks(settings)

        // Candidates: NEW cards, and started cards that can come due before the day ends.
        val rows = dsl.fetch(
            "select c.id::text, c.note_id::text, c.kind, n.note_type_id::text, n.type, ${msOf("n.created_at")}, c.state, " +
                "${msOf("c.due_at")}, c.suspended, ${msOf("c.buried_until")}, ws.override_status, n.kanji_literal, " +
                "coalesce(nullif(n.fields->>'expression', ''), de.headword) " +
                "from app.card c join app.note n on n.id = c.note_id " +
                "left join app.word_status ws on ws.entry_seq = n.entry_seq " +
                "left join ref.dict_entry de on de.seq = n.entry_seq " +
                "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null and not c.suspended " +
                "and (c.state = 'new' or c.due_at < ?::timestamptz)",
            java.sql.Timestamp(day.endMs),
        )
        val todayRows = loadToday(day.startMs, nowMs)
        val noteIds = (rows.map { it.get(1, String::class.java) } + todayRows.map { it.noteId }).toSet()
        val memberships = memberships(noteIds, decks, nowMs, settings)
        val kindOrders = kindOrders()

        var candidates = rows.map { r ->
            val noteId = r.get(1, String::class.java)
            val type = NoteBaseType.entries.first { it.wire == r.get(4, String::class.java) }
            val kind = r.get(2, String::class.java)
            QueueCandidate(
                cardId = r.get(0, String::class.java),
                noteId = noteId,
                kind = kind,
                kindOrder = kindOrders[r.get(3, String::class.java)]?.indexOf(kind)?.takeIf { it >= 0 } ?: 0,
                noteBaseType = type,
                noteCreatedAt = (r.get(5) as Number).toLong(),
                state = CardState.entries.first { it.wire == r.get(6, String::class.java) },
                dueAt = (r.get(7) as Number?)?.toLong(),
                suspended = r.get(8, Boolean::class.java),
                buriedUntil = (r.get(9) as Number?)?.toLong(),
                wordOverride = r.get(10, String::class.java)?.let { w -> WordStatus.entries.first { it.wire == w } },
                memberships = memberships[noteId].orEmpty(),
                kanjiLiteral = r.get(11, String::class.java),
                expressionKanji = if (type == NoteBaseType.VOCAB) r.get(12, String::class.java)?.let(ReviewViews::kanjiOf).orEmpty() else emptyList(),
            )
        }
        if (deckFilter != null) candidates = candidates.filter { c -> c.memberships.any { it.deckId == deckFilter } }

        val today = todayRows.map { it.copy(deckIds = memberships[it.noteId].orEmpty().map(DeckMembership::deckId)) }.map { it.review }
        val input = QueueInput(
            now = nowMs,
            day = day,
            limits = QueueLimits(
                newPerDay = settings.newPerDay,
                reviewsPerDay = settings.reviewsPerDay,
                newPerSession = settings.newPerSession,
                newShownThisSession = 0,
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
        return LoadedQueue(input, candidates)
    }

    private fun loadDecks(settings: ReviewSettings): List<QueueDeck> =
        dsl.fetch(
            "select id::text, priority, enabled, new_cards_per_day, reviews_per_day, " +
                "case when jsonb_typeof(settings->'add_kanji') = 'string' then settings->>'add_kanji' end " +
                "from app.deck where deleted_at is null order by priority, id",
        ).map { r ->
            val explicit = AddKanjiMode.entries.firstOrNull { it.wire == r.get(5, String::class.java) }
            QueueDeck(
                id = r.get(0, String::class.java),
                priority = (r.get(1) as Number).toInt(),
                enabled = r.get(2, Boolean::class.java),
                newPerDay = (r.get(3) as Number?)?.toInt(),
                reviewsPerDay = (r.get(4) as Number?)?.toInt(),
                addKanji = explicit ?: settings.decklessAddKanji,
            )
        }

    /** Index of each card kind in its note type's templates: the built-in order for built-in types, else alphabetical (jsonb loses key order). */
    private fun kindOrders(): Map<String, List<String>> {
        val builtIn = BuiltInNoteTypes.ALL.associate { it.id to it.info.templates.map { t -> t.kind } }
        val custom = dsl.fetch("select id::text, templates::text from app.note_type").associate { r ->
            val id = r.get(0, String::class.java)
            id to (builtIn[id] ?: kotobaya.server.json.readTree(r.get(1, String::class.java)).fieldNames().asSequence().sorted().toList())
        }
        return builtIn + custom
    }

    private class TodayRow(val review: TodayReview, val noteId: String) {
        fun copy(deckIds: List<String>) = TodayRow(review.copy(deckIds = deckIds), noteId)
    }

    /**
     * Today's non-undone answers (`scheduled`, `learning`, `drill`, `cram`; manual rows are not answers). `introducedNew`:
     * the card had no earlier schedule-feeding row, or the last one was a reset. `countsAsReview`: a `scheduled` row.
     */
    private fun loadToday(startMs: Long, nowMs: Long): List<TodayRow> =
        dsl.fetch(
            "select coalesce(c.merged_into_id, c.id)::text, sc.note_id::text, r.review_type, " +
                "(r.review_type = 'learning' and (prev.id is null or prev.manual_action = 'reset')) " +
                "from app.review_log r join app.card c on c.id = r.card_id " +
                "join app.card sc on sc.id = coalesce(c.merged_into_id, c.id) " +
                "left join lateral (select p.id, p.manual_action from app.review_log p join app.card pc on pc.id = p.card_id " +
                "  where coalesce(pc.merged_into_id, pc.id) = coalesce(c.merged_into_id, c.id) and p.undone_at is null " +
                "  and p.review_type in ('scheduled', 'learning', 'manual') and (p.reviewed_at, p.hlc, p.id) < (r.reviewed_at, r.hlc, r.id) " +
                "  order by p.reviewed_at desc, p.hlc desc, p.id desc limit 1) prev on true " +
                "where r.undone_at is null and r.review_type in ('scheduled', 'learning', 'drill', 'cram') " +
                "and r.reviewed_at >= ?::timestamptz and r.reviewed_at <= ?::timestamptz",
            java.sql.Timestamp(startMs), java.sql.Timestamp(nowMs),
        ).map { r ->
            val type = ReviewType.entries.first { it.wire == r.get(2, String::class.java) }
            TodayRow(
                TodayReview(
                    cardId = r.get(0, String::class.java),
                    noteId = r.get(1, String::class.java),
                    reviewType = type,
                    introducedNew = r.get(3, Boolean::class.java),
                    countsAsReview = type == ReviewType.SCHEDULED,
                    deckIds = emptyList(),
                ),
                r.get(1, String::class.java),
            )
        }

    /**
     * Deck memberships by note id, to live decks only: user/media/list decks through live `deck_item` rows (`e:<seq>`
     * matches the live vocab note of the entry, `n:<id>` resolves through `merged_into_id`), smart decks through their query.
     */
    private fun memberships(noteIds: Set<String>, decks: List<QueueDeck>, nowMs: Long, settings: ReviewSettings): Map<String, List<DeckMembership>> {
        if (noteIds.isEmpty() || decks.isEmpty()) return emptyMap()
        val ids = noteIds.toTypedArray()
        val out = HashMap<String, MutableMap<String, DeckMembership>>()
        fun add(noteId: String, m: DeckMembership) {
            val byDeck = out.getOrPut(noteId) { LinkedHashMap() }
            val old = byDeck[m.deckId]
            if (old == null || compareValuesBy(m, old, DeckMembership::position, DeckMembership::itemKey) < 0) byDeck[m.deckId] = m
        }
        val itemSql = "select di.deck_id::text, di.position, di.item_key, n.id::text from app.deck_item di " +
            "join app.deck d on d.id = di.deck_id and d.deleted_at is null and d.type <> 'smart' "
        val rows = dsl.fetch(
            itemSql + "join app.note n on n.entry_seq = di.entry_seq and n.deleted_at is null " +
                "where di.deleted_at is null and di.entry_seq is not null and n.id = any(?::uuid[]) " +
                "union all " +
                itemSql + "join app.note src on src.id = di.note_id join app.note n on n.id = coalesce(src.merged_into_id, src.id) " +
                "where di.deleted_at is null and di.note_id is not null and n.deleted_at is null and n.id = any(?::uuid[])",
            ids, ids,
        )
        for (r in rows) add(r.get(3, String::class.java), DeckMembership(r.get(0, String::class.java), (r.get(1) as Number).toLong(), r.get(2, String::class.java)))

        val smart = dsl.fetch("select id::text from app.deck where type = 'smart' and deleted_at is null").map { it.get(0, String::class.java) }
        if (smart.isNotEmpty()) {
            val cardNote = dsl.fetch(
                "select c.id::text, n.id::text, ${msOf("n.created_at")} from app.card c join app.note n on n.id = c.note_id " +
                    "where c.deleted_at is null and c.merged_into_id is null and n.deleted_at is null and n.id = any(?::uuid[])",
                ids,
            ).associate { it.get(0, String::class.java) to (it.get(1, String::class.java) to (it.get(2) as Number).toLong()) }
            val studyDay = studyDayOf(nowMs, settings.timezone, settings.dayRolloverHour).day
            for (deckId in smart) {
                val cards = try {
                    SmartDecks.cardIds(dsl, deckId, nowMs, studyDay)
                } catch (e: ApiException) {
                    continue // a stored query that no longer parses selects nothing
                }
                for (cardId in cards) cardNote[cardId]?.let { (noteId, createdAt) -> add(noteId, DeckMembership(deckId, createdAt, noteId)) }
            }
        }
        return out.mapValues { (_, byDeck) -> byDeck.values.toList() }
    }

    companion object {
        /** `QueueLimits.learnAheadMs` default of the contract: 20 minutes. */
        const val LEARN_AHEAD_MS: Long = 1_200_000
    }
}
