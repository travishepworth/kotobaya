package kotobaya.android.shell.api.actions

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.studyDayOf
import kotobaya.android.search.CardSearchOutcome
import kotobaya.android.search.LocalCardSearch
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
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.db.app.AppDatabase
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

private const val MAX_CARDS = 5000

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

/** The actions answered on the device (SHELL.md 4.3); the others are forwarded (P-1 … P-3). */
internal val LOCAL_BULK_ACTIONS: Set<String> = setOf("suspend", "unsuspend", "bury", "unbury", "flag")

/** What a card-search `query` selected: live card ids ascending, or the parser's errors. */
sealed interface CardQueryResult {
    data class Ids(val ids: List<String>) : CardQueryResult

    /** `code` = the core `SearchErrorCode` in lower case, `position` = its UTF-16 offset. */
    data class Invalid(val errors: List<Pair<String, Int>>) : CardQueryResult
}

/**
 * The card search language over the replica for the `query` selector of `bulkCards` (the core parser and SQL
 * compiler, as `listCards` and the server use). Production binds [LocalCardQuery] over the app driver.
 */
fun interface CardQuery {
    fun select(query: String, nowMs: Long): CardQueryResult
}

/**
 * [CardQuery] over the replica through the existing "My cards" search ([LocalCardSearch]: core
 * `DefaultSearchQueryParser` + `SearchSqlCompiler`, SQLite dialect with its residual check, C-24 dictionary forms).
 * A query the parser reports any error for is refused, as the server does (`SmartDecks.parse`).
 */
class LocalCardQuery(
    private val driver: SqlDriver,
    private val db: AppDatabase,
    private val dictionary: InstalledDictionary?,
) : CardQuery {
    override fun select(query: String, nowMs: Long): CardQueryResult {
        val parsed = DefaultSearchQueryParser.parse(query)
        if (parsed.query == null || parsed.errors.isNotEmpty()) {
            return CardQueryResult.Invalid(parsed.errors.map { it.code.name.lowercase() to it.span.start })
        }
        return when (val r = LocalCardSearch(driver, db, { nowMs }, dictionary).search(query)) {
            is CardSearchOutcome.Results -> CardQueryResult.Ids(r.hits.map { it.cardId }.distinct().sorted())
            is CardSearchOutcome.ParseError -> CardQueryResult.Invalid(r.errors.map { it.code.name.lowercase() to it.span.start })
            is CardSearchOutcome.Failed -> error("the local card search failed")
        }
    }
}

/** A validated `CardBulkRequest` (server `BulkApi.parse` + `validateAction`). */
private class BulkRequest(val ids: List<String>?, val query: String?, val type: String, val action: JsonObject, val dryRun: Boolean)

private fun bulkRequest(body: JsonObject): BulkRequest {
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
            if (type != null) validateAction(type, action, e)
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

/** A refused op's payload pointer as the request property it came from. */
private fun bulkPointer(r: MintRefusal.Invalid): String = when (r.pointer.removePrefix("/payload")) {
    "/until" -> "/action/until"
    "/flags" -> "/action/flags"
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

/**
 * `bulkCards` (SHELL.md 4.3). The request is validated locally first (the server's checks, same `422`s). Actions
 * `suspend`, `unsuspend`, `bury`, `unbury`, `flag` are answered here for both selectors and for `dry_run`; any other
 * action is forwarded ([Fallthrough]: rules P-1 … P-3, `503 shell_offline` offline).
 *
 * As the server (`BulkApi.bulk`): `card_ids` that are unknown, deleted, of a deleted note or merged away are not
 * redirected but counted in `skipped`; a `query` is the card search (`422 invalid_query`, more than 5000 cards
 * `422 bulk_too_large`); every matched card gets its op, also one already in the target state (it bumps the clock);
 * bury `until` defaults to the end of the current study day and must be in the future. All ops of one call are
 * minted in ONE transaction through [LocalWrites], in card-id order, all or none: a refusal of any op mints none.
 */
internal fun bulkCards(call: LocalCall): ShellResponse {
    call.query()
    val req = bulkRequest(call.bodyObject())
    if (req.type !in LOCAL_BULK_ACTIONS) return Fallthrough
    val now = call.services.get<ShellIdentity>().nowMs()
    val queried: List<String>? = req.query?.let { q ->
        val selector = runCatching { call.services.get<CardQuery>() }.getOrNull() ?: return Fallthrough
        when (val r = selector.select(q, now)) {
            is CardQueryResult.Invalid -> throw ProblemException(invalidQuery(r.errors))
            is CardQueryResult.Ids -> r.ids.also {
                if (it.size > MAX_CARDS) {
                    throw ProblemException(
                        Problem(
                            422, "bulk_too_large", "Too many cards", "The query matches more than $MAX_CARDS cards; nothing was applied.",
                            listOf(FieldError("/query", "bulk_too_large", "More than $MAX_CARDS cards.")),
                        ),
                    )
                }
            }
        }
    }
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
        val (ids, skipped) = if (req.ids != null) {
            val live = liveCards(t, req.ids)
            live to (req.ids.size - live.size)
        } else {
            // the search ran on a reader just before; a card deleted since then is no longer matched
            liveCards(t, checkNotNull(queried)) to 0
        }
        if (until != null && until <= now) {
            throw ProblemException(Problems.validationFailed("/action/until", "out_of_range", "Must be in the future."))
        }
        val requests = ids.map { id ->
            when (req.type) {
                "suspend" -> MintRequest(OpType.CARD_SUSPENDED, jsonObj("id" to JsonString(id)))
                "unsuspend" -> MintRequest(OpType.CARD_UNSUSPENDED, jsonObj("id" to JsonString(id)))
                "unbury" -> MintRequest(OpType.CARD_UNBURIED, jsonObj("id" to JsonString(id)))
                "bury" -> MintRequest(OpType.CARD_BURIED, jsonObj("id" to JsonString(id), "until" to JsonNumber(checkNotNull(until).toDouble())))
                "flag" -> MintRequest(
                    OpType.CARD_FLAG_SET,
                    jsonObj("id" to JsonString(id), "flags" to JsonNumber(checkNotNull(integral(req.action.members["flags"])).toDouble())),
                )
                else -> error("not a local bulk action: ${req.type}")
            }
        }
        val answer = result(ids.size, ids.size, skipped, requests.size, req.dryRun)
        if (req.dryRun || requests.isEmpty()) WritePlan.Answer(answer) else WritePlan.Mint(requests, ::bulkPointer) { _, _ -> answer }
    }
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
