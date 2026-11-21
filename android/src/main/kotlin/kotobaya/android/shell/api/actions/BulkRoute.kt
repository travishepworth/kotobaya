package kotobaya.android.shell.api.actions

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.studyDayOf
import kotobaya.android.queue.rolloverMs
import kotobaya.android.search.rows
import kotobaya.android.shell.api.Fallthrough
import kotobaya.android.shell.api.FieldError
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.notify.DueTrigger
import kotobaya.android.shell.api.decks.DeckMints
import kotobaya.android.shell.api.decks.DeckViews
import kotobaya.android.shell.api.decks.POSITION_STEP
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.db.app.AppDatabase
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.DictFormRow
import kotobaya.search.sql.ResidualRow
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import kotobaya.search.sql.SmartDeckRef
import kotobaya.search.sql.SqlFragment
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private const val MAX_CARDS = 5000
private const val MAX_TAGS_PER_NOTE = 200

/** Params each `bulkCards` action takes besides `type` (server `BulkApi.ACTION_PARAMS`); anything else is a `422`. */
private val ACTION_PARAMS: Map<String, Set<String>> = mapOf(
    "suspend" to emptySet(),
    "unsuspend" to emptySet(),
    "bury" to setOf("until"),
    "unbury" to emptySet(),
    "flag" to setOf("flags"),
    "set_preset" to setOf("preset_id"),
    "add_tags" to setOf("tags"),
    "remove_tags" to setOf("tags"),
    "move" to setOf("to_deck_id", "from_deck_id"),
    "delete" to emptySet(),
)

/** What a card-search `query` selected: live card ids ascending, the parser's errors, or "ask the server". */
sealed interface CardQueryResult {
    data class Ids(val ids: List<String>) : CardQueryResult

    /** `code` = the core `SearchErrorCode` in lower case, `position` = its UTF-16 offset. */
    data class Invalid(val errors: List<Pair<String, Int>>) : CardQueryResult

    /** The device cannot select exactly what the server would ([reason], for logs): the request is forwarded. */
    data class Forward(val reason: String) : CardQueryResult
}

/**
 * The card search language over the replica for the `query` selector of `bulkCards` (the core parser and SQL
 * compiler, as `listCards` and the server use). Production binds [LocalCardQuery] over the app driver.
 */
fun interface CardQuery {
    fun select(query: String, nowMs: Long): CardQueryResult
}

/**
 * [CardQuery] over the replica, compiled exactly as the server's `CardSearch.compile` but in the SQLite dialect: core
 * `DefaultSearchQueryParser` (any parser error is refused, as `SmartDecks.parse`), a tz-aware [SearchContext] from
 * the replica's settings, the **live smart decks** of the replica as [SmartDeckRef]s (a `deck:` term naming one
 * selects by its query, also negated), `is:hot` matching nothing (the server passes no hot set either), the
 * residual check of the SQLite dialect, and the C-24 dictionary forms of the installed package.
 *
 * The one place the device could select differently is the dictionary-form lookup: a text pattern matching more than
 * [dictFormsMaxSeqs] collection entries (the guard `LocalCardSearch` uses for "My cards") is not looked up in full
 * here; instead the selection answers [CardQueryResult.Forward], so a bulk call never acts on a silently different set.
 */
class LocalCardQuery(
    private val driver: SqlDriver,
    private val db: AppDatabase,
    private val dictionary: InstalledDictionary?,
    private val dictFormsMaxSeqs: Int = DICT_FORMS_MAX_SEQS,
) : CardQuery {
    override fun select(query: String, nowMs: Long): CardQueryResult {
        val parsed = DefaultSearchQueryParser.parse(query)
        val node = parsed.query
        if (node == null || parsed.errors.isNotEmpty()) {
            return CardQueryResult.Invalid(parsed.errors.map { it.code.name.lowercase() to it.span.start })
        }
        val st = QueueSettings.from(db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })
        val today = studyDayOf(nowMs, st.timezone, st.dayRolloverHour)
        val smart = driver.rows("SELECT name, query FROM deck WHERE type = 'smart' AND deleted_at IS NULL AND query IS NOT NULL") {
            it.getString(0).orEmpty() to it.getString(1).orEmpty()
        }.mapNotNull { (name, q) ->
            val p = DefaultSearchQueryParser.parse(q)
            p.query?.takeIf { p.errors.isEmpty() }?.let { SmartDeckRef(name, it) }
        }
        var capped = false
        val ctx = SearchContext(
            studyDay = today.day,
            nowMs = nowMs,
            leechThreshold = st.leechThreshold,
            smartDecks = smart,
            dayStart = { off -> rolloverMs(st.timezone, st.dayRolloverHour, today.date.plusDays(off.toLong())) },
            dictForms = dictForms { capped = true },
        )
        val compiled = SearchSqlCompiler.compile(node, Dialect.SQLITE, ctx)
        if (capped) return CardQueryResult.Forward("a text term matches more than $dictFormsMaxSeqs dictionary entries")
        val f = compiled.select()
        val ids = driver.rows(f.sql, f.params) { c ->
            val id = checkNotNull(c.getString(0))
            if (!compiled.residual) {
                id
            } else {
                val probes = compiled.probes.indices.map { (c.getLong(7 + it) ?: 0L) != 0L }
                val row = ResidualRow(
                    c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                    c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getString(6).orEmpty(), probes,
                )
                id.takeIf { compiled.accepts(row) }
            }
        }
        return CardQueryResult.Ids(ids.filterNotNull().distinct().sorted())
    }

    /** C-24 over the installed package, intersected with the collection's entries; [onCap] when a pattern is too broad. */
    private fun dictForms(onCap: () -> Unit): ((SqlFragment) -> List<DictFormRow>)? {
        val dict = dictionary?.takeIf { it.isAvailable() } ?: return null
        val collection: Set<Long> by lazy {
            driver.rows("SELECT DISTINCT entry_seq FROM note WHERE entry_seq IS NOT NULL AND deleted_at IS NULL") { it.getLong(0) ?: 0L }.toHashSet()
        }
        return { f ->
            val rows = dict.formRows(f.sql, f.params.map { it.toString() }) { it in collection }.orEmpty()
            if (rows.mapTo(HashSet()) { it.seq }.size > dictFormsMaxSeqs) onCap()
            rows
        }
    }

    companion object {
        /** The "My cards" guard (`LocalCardSearch`): beyond it the device does not trust its dictionary-form lookup. */
        const val DICT_FORMS_MAX_SEQS: Int = 3000
    }
}

/** A validated `CardBulkRequest` (server `BulkApi.parse` + `validateAction`). */
private class BulkRequest(val ids: List<String>?, val query: String?, val type: String, val action: JsonObject, val dryRun: Boolean)

private fun bulkRequest(body: JsonObject, schedule: Boolean = false): BulkRequest {
    val e = FieldErrors()
    e.unknownKeys(body, setOf("card_ids", "query", "action", "dry_run"))
    val m = body.members
    val hasIds = "card_ids" in m
    val hasQuery = "query" in m
    if (hasIds == hasQuery) e.add("", "invalid_value", "Exactly one of card_ids and query.")
    var ids: List<String>? = null
    if (hasIds) {
        val items = (m["card_ids"] as? JsonArray)?.items
        if (items == null || items.size !in 1..MAX_CARDS) {
            e.add("/card_ids", "out_of_range", "An array of 1 to $MAX_CARDS ids.")
        } else {
            val list = items.mapIndexedNotNull { i, v -> e.uuidValue(v, "/card_ids/$i") }
            if (list.size == items.size && list.toSet().size != list.size) e.add("/card_ids", "invalid_value", "Ids must be unique.")
            ids = list
        }
    }
    val query = if (hasQuery) e.string(body, "query", 1, 2000) else null
    val dry = m["dry_run"]?.let {
        (it as? JsonBool)?.value ?: run { e.add("/dry_run", "invalid_type", "Must be a boolean."); false }
    } ?: false
    val action = m["action"]
    var type: String? = null
    when {
        action == null -> e.add("/action", "required", "action is required.")
        action !is JsonObject -> e.add("/action", "invalid_type", "Must be an object.")
        else -> {
            type = e.enumValue(action, "type", ACTION_PARAMS.keys + setOf("reschedule", "reset"), at = "/action/type", required = true)
            if (type != null) if (schedule) validateScheduleAction(type, action, e) else validateAction(type, action, e)
        }
    }
    e.throwIfAny()
    return BulkRequest(ids, query, checkNotNull(type), action as JsonObject, dry)
}

private fun validateAction(type: String, action: JsonObject, e: FieldErrors) {
    val allowed = ACTION_PARAMS[type]
    if (allowed == null) {
        e.add("/action/type", "invalid_value", "Not an action of this operation.")
        return
    }
    for (k in action.members.keys.filter { it != "type" && it !in allowed }) {
        e.add("/action/${escapePointer(k)}", "unknown_field", "$k does not apply to $type.")
    }
    fun required(key: String): JsonValue? = action.members[key].also { if (it == null) e.add("/action/$key", "required", "$key is required.") }
    fun uuid(v: JsonValue, at: String) {
        if (!UUID_RE.matches(v.text() ?: "")) e.add(at, "invalid_value", "Must be a lowercase UUID.")
    }
    when (type) {
        "bury" -> action.members["until"]?.let { e.instant(it, "/action/until") }
        "flag" -> required("flags")?.let { if (integral(it)?.takeIf { f -> f in 0..7 } == null) e.add("/action/flags", "out_of_range", "Must be 0 to 7.") }
        "set_preset" -> required("preset_id")?.let { uuid(it, "/action/preset_id") }
        "add_tags", "remove_tags" -> required("tags")?.let { e.tagList(it, "/action/tags", 1, 100) }
        "move" -> {
            required("to_deck_id")?.let { uuid(it, "/action/to_deck_id") }
            action.members["from_deck_id"]?.let { uuid(it, "/action/from_deck_id") }
        }
    }
}

/** `bulkScheduleCards` actions (server `BulkApi.schedule`): `reschedule` needs `due_at`, `reset` takes nothing. */
private fun validateScheduleAction(type: String, action: JsonObject, e: FieldErrors) {
    e.unknownKeys(action, setOf("type", "due_at"))
    when (type) {
        "reschedule" -> {
            val due = action.members["due_at"]
            if (due == null) e.add("/action/due_at", "required", "due_at is required.") else e.instant(due, "/action/due_at")
        }
        "reset" -> if ("due_at" in action.members) e.add("/action/due_at", "unknown_field", "reset takes no parameters.")
        else -> e.add("/action/type", "invalid_value", "Must be reschedule or reset.")
    }
}

/** A refused op's payload pointer as the request property it came from. */
private fun bulkPointer(r: MintRefusal.Invalid): String = when (r.pointer.removePrefix("/payload")) {
    "/until" -> "/action/until"
    "/flags" -> "/action/flags"
    "/preset_id" -> "/action/preset_id"
    "/set/tags" -> "/action/tags"
    "/position" -> "/action/to_deck_id"
    else -> ""
}

/** Live (card and note not tombstoned, card not merged away) cards among [ids], ascending. */
private fun liveCards(t: LearningTables, ids: Collection<String>): List<String> = ids.filter { id ->
    val card = t.get(LearningTable.CARD, listOf(JsonString(id))) ?: return@filter false
    if (!card["deleted_at"].isNullish() || !card["merged_into_id"].isNullish()) return@filter false
    val note = card["note_id"].text()?.let { t.get(LearningTable.NOTE, listOf(JsonString(it))) } ?: return@filter false
    note["deleted_at"].isNullish()
}.sorted()

private fun result(matched: Int, changed: Int, skipped: Int, ops: Int, dry: Boolean): JsonObject = jsonObj(
    "matched" to JsonNumber(matched.toDouble()),
    "changed" to JsonNumber(changed.toDouble()),
    "skipped" to JsonNumber(skipped.toDouble()),
    "ops_emitted" to JsonNumber((if (dry) 0 else ops).toDouble()),
    "dry_run" to JsonBool(dry),
)

/** The cards a call selected (live, ascending) and how many selector ids were not usable (server `Selection`). */
private class Selection(val ids: List<String>, val unusable: Int)

/** What an action decided: the ops to mint, and the `changed` / `skipped` it counts on top of the unusable ids. */
private class Plan(val requests: List<MintRequest>, val changed: Int, val skipped: Int)

/**
 * The selection of a call, read inside the write transaction: `card_ids` are the live cards among them (the rest
 * counted unusable), a `query` was run on a reader just before and is narrowed to cards still live. Over
 * [MAX_CARDS] live cards → `422 bulk_too_large`. Null for a query selector that must be forwarded (no [CardQuery]).
 */
private fun selection(t: LearningTables, req: BulkRequest, queried: List<String>?): Selection {
    if (req.ids != null) {
        val live = liveCards(t, req.ids)
        return Selection(live, req.ids.size - live.size)
    }
    val live = liveCards(t, checkNotNull(queried))
    // counted on the live cards, the set that would be acted on (the server's compiler matches live cards only)
    if (live.size > MAX_CARDS) {
        throw ProblemException(
            Problem(
                422, "bulk_too_large", "Too many cards", "The query matches more than $MAX_CARDS cards; nothing was applied.",
                listOf(FieldError("/query", "bulk_too_large", "More than $MAX_CARDS cards.")),
            ),
        )
    }
    return Selection(live, 0)
}

/** Resolves a `query` selector on the device: the ids, or a problem / a "forward it" (null) answer. */
private fun resolveQuery(call: LocalCall, req: BulkRequest, now: Long): Pair<Boolean, List<String>?> {
    val q = req.query ?: return true to null
    val selector = runCatching { call.services.get<CardQuery>() }.getOrNull() ?: return false to null
    return when (val r = selector.select(q, now)) {
        is CardQueryResult.Invalid -> throw ProblemException(invalidQuery(r.errors))
        is CardQueryResult.Ids -> true to r.ids
        // the device cannot select what the server would: let the server act (offline: 503 shell_offline)
        is CardQueryResult.Forward -> false to null
    }
}

/** `add_tags` / `remove_tags` (server `BulkApi.tags`): one `note.updated` per distinct note whose tag set changes. */
private fun tagPlan(t: LearningTables, sel: Selection, action: JsonObject, add: Boolean): Plan {
    val e = FieldErrors()
    val wanted = action.members["tags"]?.let { e.tagList(it, "/action/tags", 1, 100) }
    e.throwIfAny()
    val change = checkNotNull(wanted).toSet()
    val requests = mutableListOf<MintRequest>()
    var skipped = 0
    var tooMany = 0
    val seen = HashSet<String>()
    for (cardId in sel.ids) {
        val noteId = t.get(LearningTable.CARD, listOf(JsonString(cardId)))?.get("note_id").text() ?: continue
        if (!seen.add(noteId)) continue
        val note = t.get(LearningTable.NOTE, listOf(JsonString(noteId))) ?: continue
        val before = ((note["tags"] as? JsonArray)?.items.orEmpty().mapNotNull { it.text() }).toSortedSet(CodePointOrder)
        val next = (if (add) before + change else before - change).toSortedSet(CodePointOrder)
        if (next.size > MAX_TAGS_PER_NOTE) tooMany++
        if (next == before) skipped++ else requests += CardMints.noteTags(noteId, next.toList())
    }
    if (tooMany > 0) {
        throw ProblemException(
            Problems.validationFailed("/action/tags", "out_of_range", "A note has at most $MAX_TAGS_PER_NOTE tags ($tooMany would exceed); nothing applied."),
        )
    }
    return Plan(requests, requests.size, skipped)
}

private fun readOnlyDeck(id: String): Nothing =
    throw ProblemException(Problems.conflict("deck_read_only", "Deck $id is not a live user deck."))

/**
 * `move` (server `BulkApi.move`): both decks must be live user decks (`409 deck_read_only`); one `deck.item_put` per
 * distinct note at its existing position in `to_deck_id` or appended after the deck's last, and with `from_deck_id`
 * (other than `to_deck_id`) a `deck.item_removed` for every live key of that identity there. The server emits as it
 * goes, so a second note of the same key finds the first one's row; [assigned] / [removed] are that state.
 */
private fun movePlan(t: LearningTables, sel: Selection, action: JsonObject): Plan {
    val to = checkNotNull(action.members["to_deck_id"].text())
    val from = action.members["from_deck_id"].text()
    for (deck in listOfNotNull(to, from)) {
        val row = DeckViews.deckRow(t, deck)
        if (row == null || !DeckViews.isLive(row) || DeckViews.type(row) != "user") readOnlyDeck(deck)
    }
    var next = DeckViews.maxPosition(t, to)
    val notes = LinkedHashMap<String, Long?>()
    for (cardId in sel.ids) {
        val noteId = t.get(LearningTable.CARD, listOf(JsonString(cardId)))?.get("note_id").text() ?: continue
        if (noteId in notes) continue
        notes[noteId] = integral(t.get(LearningTable.NOTE, listOf(JsonString(noteId)))?.get("entry_seq"))
    }
    val assigned = HashMap<String, Long>()
    val removed = HashSet<String>()
    val requests = mutableListOf<MintRequest>()
    for ((noteId, entrySeq) in notes) {
        val key = entrySeq?.let { "e:$it" } ?: "n:$noteId"
        val position = assigned[key] ?: DeckViews.livePosition(t, to, key) ?: (next + POSITION_STEP).also { next = it }
        assigned[key] = position
        requests += DeckMints.itemPut(to, key, position)
        if (from != null && from != to) {
            DeckViews.liveKeysOf(t, from, key).filter { removed.add(it) }.forEach { requests += DeckMints.itemRemoved(from, it) }
        }
    }
    return Plan(requests, notes.size, 0)
}

/**
 * `bulkCards` (SHELL.md 4.3, C-31: every action is local). The request is validated locally first (the server's
 * checks, same `422`s). Every action, for both selectors and for `dry_run`, is answered here.
 *
 * As the server (`BulkApi.bulk`): `card_ids` that are unknown, deleted, of a deleted note or merged away are not
 * redirected but counted in `skipped`; a `query` is the card search (`422 invalid_query`, more than 5000 cards
 * `422 bulk_too_large`; live smart decks resolve `deck:` terms; a selection the device cannot
 * reproduce exactly is forwarded); every matched card gets its op, also one already in the target state (it bumps the clock;
 * except the tag actions, which skip a note whose tags would not change, and `delete`, which skips tombstoned cards as
 * not live); bury `until` defaults to the end of the current study day and must be in the future; `set_preset` needs a
 * live preset (`422 /action/preset_id`); `move` needs live user decks (`409 deck_read_only`). All ops of one call are
 * minted in ONE transaction through [LocalWrites], in card-id order, all or none: a refusal of any op mints none.
 */
internal fun bulkCards(call: LocalCall): ShellResponse {
    call.query()
    val req = bulkRequest(call.bodyObject())
    val now = call.services.get<ShellIdentity>().nowMs()
    val (local, queried) = resolveQuery(call, req, now)
    if (!local) return Fallthrough
    val until: Long? = if (req.type == "bury") {
        val given = req.action.members["until"]?.let { FieldErrors().instant(it, "/action/until") }
        given ?: run {
            val st = QueueSettings.from(call.services.get<AppDatabase>().localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })
            studyDayOf(now, st.timezone, st.dayRolloverHour).day.endMs
        }
    } else {
        null
    }
    val body = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<JsonObject> { tx ->
        val t = tx.learning
        val sel = selection(t, req, queried)
        if (until != null && until <= now) {
            throw ProblemException(Problems.validationFailed("/action/until", "out_of_range", "Must be in the future."))
        }
        val plan: Plan = when (req.type) {
            "suspend" -> Plan(sel.ids.map(CardMints::suspended), sel.ids.size, 0)
            "unsuspend" -> Plan(sel.ids.map(CardMints::unsuspended), sel.ids.size, 0)
            "unbury" -> Plan(sel.ids.map(CardMints::unburied), sel.ids.size, 0)
            "delete" -> Plan(sel.ids.map(CardMints::deleted), sel.ids.size, 0)
            "bury" -> Plan(sel.ids.map { CardMints.buried(it, checkNotNull(until)) }, sel.ids.size, 0)
            "flag" -> {
                val flags = checkNotNull(integral(req.action.members["flags"]))
                Plan(sel.ids.map { CardMints.flagSet(it, flags) }, sel.ids.size, 0)
            }
            "set_preset" -> {
                val preset = checkNotNull(req.action.members["preset_id"].text())
                val row = t.get(LearningTable.FSRS_PARAMS, listOf(JsonString(preset)))
                if (row == null || !row["deleted_at"].isNullish()) {
                    throw ProblemException(Problems.validationFailed("/action/preset_id", "invalid_value", "No such live preset."))
                }
                Plan(sel.ids.map { CardMints.presetSet(it, preset) }, sel.ids.size, 0)
            }
            "add_tags", "remove_tags" -> tagPlan(t, sel, req.action, add = req.type == "add_tags")
            "move" -> movePlan(t, sel, req.action)
            else -> error("not a bulkCards action: ${req.type}")
        }
        val answer = result(sel.ids.size, plan.changed, sel.unusable + plan.skipped, plan.requests.size, req.dryRun)
        if (req.dryRun || plan.requests.isEmpty()) WritePlan.Answer(answer) else WritePlan.Mint(plan.requests, ::bulkPointer) { _, _ -> answer }
    }
    return ShellResponse.json(body)
}

/**
 * `bulkScheduleCards` (SHELL.md 4.3, C-31: local): same selectors, limits, order, transaction and `dry_run` as
 * [bulkCards]; one manual `review.logged` per processed card (device-minted UUIDv7 id, `reviewed_at` = the device's
 * now, `utc_offset_min` of the replica's time zone), `reset` of a `new` card skipped, `reschedule` with a `new` card
 * among the matches `422 invalid_state` with nothing minted. The card is recomputed by the apply path (replay).
 */
internal fun bulkScheduleCards(call: LocalCall): ShellResponse {
    call.query()
    val req = bulkRequest(call.bodyObject(), schedule = true)
    val now = call.services.get<ShellIdentity>().nowMs()
    val (local, queried) = resolveQuery(call, req, now)
    if (!local) return Fallthrough
    val st = QueueSettings.from(call.services.get<AppDatabase>().localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })
    val offset = st.timezone.rules.getOffset(java.time.Instant.ofEpochMilli(now)).totalSeconds / 60
    val reschedule = req.type == "reschedule"
    val dueMs = if (reschedule) req.action.members["due_at"]?.let { FieldErrors().instant(it, "/action/due_at") } else null
    var minted = false
    val body = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<JsonObject> { tx ->
        val t = tx.learning
        val sel = selection(t, req, queried)
        val isNew = sel.ids.associateWith { id ->
            t.get(LearningTable.CARD, listOf(JsonString(id)))?.get("state").text().let { it == null || it == "new" }
        }
        if (reschedule) {
            val fresh = isNew.values.count { it }
            if (fresh > 0) {
                throw ProblemException(
                    Problems.validationFailed("/action/type", "invalid_state", "$fresh matched card(s) are new and have no memory state to keep."),
                )
            }
        }
        var skipped = 0
        val requests = mutableListOf<MintRequest>()
        for (id in sel.ids) {
            if (!reschedule && isNew.getValue(id)) {
                skipped++
                continue
            }
            requests += CardMints.manualReview(CardMints.newId(now), id, now, offset, if (reschedule) "set_due" else "reset", dueMs)
        }
        val answer = result(sel.ids.size, requests.size, sel.unusable + skipped, requests.size, req.dryRun)
        if (req.dryRun || requests.isEmpty()) {
            WritePlan.Answer(answer)
        } else {
            WritePlan.Mint(requests, { r -> if (r.pointer.removePrefix("/payload") == "/manual_due_at") "/action/due_at" else "" }) { _, _ ->
                minted = true
                answer
            }
        }
    }
    if (minted) runCatching { call.services.get<DueTrigger>().fireInBackground() }
    return ShellResponse.json(body)
}

/** The server's `422 invalid_query` (`SmartDecks.parse`): one error per parser error, with its UTF-16 `position`. */
private fun invalidQuery(errors: List<Pair<String, Int>>): Problem {
    val list = errors.map { (code, pos) ->
        jsonObj(
            "pointer" to JsonString("/query"), "code" to JsonString(code),
            "detail" to JsonString("Search error $code at $pos."), "position" to JsonNumber(pos.toDouble()),
        )
    }
    val detail = errors.firstOrNull()?.let { (code, pos) -> "Search error $code at $pos." } ?: "The query does not parse."
    return Problem(422, "invalid_query", "Invalid search query", detail, extra = mapOf("errors" to JsonArray(list)))
}
