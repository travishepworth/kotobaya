package kotobaya.android.shell.api.decks

import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.android.shell.api.Fallthrough
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.enumValue
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.string
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.browse.invalidQuery
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.memoized
import kotobaya.android.shell.api.identity.ShellIdentity
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.store.UuidV7Source

private val DECK_TYPES = setOf("user", "media", "list", "smart")
private val LIST_KEY = Regex("^[a-z0-9][a-z0-9-]*$")
private val CREATE_KEYS = setOf("id", "type", "name", "priority", "enabled", "new_cards_per_day", "reviews_per_day", "settings", "query", "list_key")
private val PATCH_KEYS = setOf("name", "priority", "enabled", "new_cards_per_day", "reviews_per_day", "query", "settings")
private val PATCH_IMMUTABLE = setOf("id", "type", "media_id", "list_key")

/** A deck `id` the request omitted: UUIDv7 from the device clock (L-2). */
private val ids = UuidV7Source(SecureRandom().asKotlinRandom())

private fun notFound(): Nothing = throw ProblemException(Problems.notFound("No such deck."))

private fun gone(): Nothing = throw ProblemException(Problems.gone("The deck was deleted."))

private fun semantic(pointer: String, detail: String, code: String = "invalid_value"): Nothing =
    throw ProblemException(Problems.validationFailed(pointer, code, detail))

/** The server's `jsonBody()`: absent, not JSON or not an object → `400 invalid_request`. */
internal fun objectBody(call: LocalCall): JsonObject =
    call.bodyValue() as? JsonObject ?: throw ProblemException(Problems.invalidRequest("The body must be a JSON object."))

/** `true` / `false` of `include_deleted` (server `includeDeleted`). */
private fun includeDeleted(raw: String?, e: FieldErrors): Boolean = when (raw) {
    null, "false" -> false
    "true" -> true
    else -> {
        e.add("/include_deleted", "invalid_value", "Must be true or false.")
        false
    }
}

/** `null` is allowed (no per-deck limit); otherwise an integer in `0..max` (server `nullableInt`). */
private fun FieldErrors.nullableInt(v: JsonValue, at: String, max: Int): Int? = if (v == JsonNull) null else deckInt(v, at, 0, max)

private fun FieldErrors.bool(v: JsonValue, at: String): Boolean? = (v as? JsonBool)?.value ?: null.also { add(at, "invalid_type", "Must be a boolean.") }

/** Server `SmartDecks.parse`: a query that does not parse is `422 invalid_query`, one error per parser error. */
internal fun requireParses(query: String) {
    val r = DefaultSearchQueryParser.parse(query)
    if (r.query != null && r.errors.isEmpty()) return
    throw ProblemException(invalidQuery(r.errors.map { it.code.name.lowercase() to it.span.start }))
}

/** Live decks first by `(priority, id)` (the draw order, server `order by d.priority, d.id`). */
private val drawOrder: Comparator<Row> = compareBy<Row>({ (it["priority"] as? JsonNumber)?.value ?: 0.0 }, { it["id"].text().orEmpty() })

/** What `listDecks` reads: decks, their items, the notes and cards of their progress, word status and the reading setting. */
private val DECK_LIST_TABLES = arrayOf(
    LearningTable.DECK, LearningTable.DECK_ITEM, LearningTable.NOTE, LearningTable.CARD, LearningTable.WORD_STATUS, LearningTable.SETTINGS,
)

/** `listDecks`: every deck of the replica (tombstones with `include_deleted=true`), optionally of one `type`. */
internal fun listDecks(call: LocalCall): ShellResponse {
    val q = call.query("type", "include_deleted")
    val e = FieldErrors()
    val deleted = includeDeleted(q.first("include_deleted"), e)
    val type = q.first("type")
    if (type != null && type !in DECK_TYPES) e.add("/type", "invalid_value", "Must be one of ${DECK_TYPES.joinToString(", ")}.")
    e.throwIfAny()
    // PERF-LF: kept until a table the decks, their items or their progress read changes
    val items = call.memoized("listDecks|$type|$deleted", null, DECK_LIST_TABLES) {
        call.services.get<SqlDelightDeviceStore>().read { tx ->
            val t = tx.learning
            t.all(LearningTable.DECK)
                .filter { (deleted || DeckViews.isLive(it)) && (type == null || DeckViews.type(it) == type) }
                .sortedWith(drawOrder)
                .map { DeckViews.deck(t, it) }
        }
    }
    return ShellResponse.json(jsonObj("items" to kotobaya.contracts.sync.JsonArray(items)))
}

/** `getDeck`: `410` for a tombstone unless `include_deleted=true`. */
internal fun getDeck(call: LocalCall): ShellResponse {
    val q = call.query("include_deleted")
    val id = call.pathId("deck_id", "deck")
    val e = FieldErrors()
    val deleted = includeDeleted(q.first("include_deleted"), e)
    e.throwIfAny()
    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val row = DeckViews.deckRow(tx.learning, id) ?: notFound()
        if (!deleted && !DeckViews.isLive(row)) gone()
        DeckViews.deck(tx.learning, row)
    }
    return ShellResponse.json(body)
}

/**
 * `createDeck` (SHELL.md 4.3): validated locally exactly as the server; an existing `id` (live or deleted) answers
 * `200` with that deck and mints nothing (L-4). `user` / `smart`: one `deck.created` minted by the device, `201`.
 * `list`: `deck.created` for a list deck is server-only and needs the server's `deck-build` job, so the request is
 * forwarded (P-1 … P-3; `503 shell_offline` offline). `media` is not an accepted `type` (`422`, as the server).
 */
internal fun createDeck(call: LocalCall): ShellResponse {
    call.query()
    val body = objectBody(call)
    val m = body.members
    val e = FieldErrors()
    e.unknownKeys(body, CREATE_KEYS)
    val id = m["id"]?.let { e.uuidValue(it, "/id") }
    val type = e.enumValue(body, "type", setOf("user", "smart", "list"), required = true)
    val name = e.string(body, "name", 1, 200, required = type == "user" || type == "smart")
    val priority = m["priority"]?.let { e.deckInt(it, "/priority", -1_000_000, 1_000_000) }
    val enabled = m["enabled"]?.let { e.bool(it, "/enabled") }
    val newPerDay = m["new_cards_per_day"]?.let { e.nullableInt(it, "/new_cards_per_day", 9999) }
    val reviewsPerDay = m["reviews_per_day"]?.let { e.nullableInt(it, "/reviews_per_day", 99999) }
    val settings = m["settings"]?.let { e.deckSettings(it, create = true) }
    var query: String? = null
    when (type) {
        "smart" -> {
            query = e.string(body, "query", 1, 2000, required = true)
            if ("list_key" in m) e.add("/list_key", "unexpected", "list_key belongs to list decks.")
        }
        "list" -> {
            val v = m["list_key"]
            when {
                v == null -> e.add("/list_key", "required", "list_key is required.")
                (v as? JsonString)?.value?.let { LIST_KEY.matches(it) && it.length <= 200 } != true -> e.add("/list_key", "invalid_value", "A lowercase slug.")
            }
            if ("query" in m) e.add("/query", "unexpected", "query belongs to smart decks.")
        }
        "user" -> {
            if ("query" in m) e.add("/query", "unexpected", "query belongs to smart decks.")
            if ("list_key" in m) e.add("/list_key", "unexpected", "list_key belongs to list decks.")
        }
    }
    e.throwIfAny()
    query?.let(::requireParses)

    val store = call.services.get<SqlDelightDeviceStore>()
    if (type == "list") {
        // L-4 still holds for a retry of a list deck this device already has; anything else is the server's
        val existing = id?.let { i -> store.read { tx -> DeckViews.deckRow(tx.learning, i)?.let { DeckViews.deck(tx.learning, it) } } }
        return existing?.let { ShellResponse.json(it) } ?: Fallthrough
    }
    val now = call.services.get<ShellIdentity>().nowMs()
    val newId = id ?: ids.newOpId(now)
    val (status, deck) = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<Pair<Int, JsonObject>> { tx ->
        DeckViews.deckRow(tx.learning, newId)?.let { return@exclusive WritePlan.Answer(200 to DeckViews.deck(tx.learning, it)) }
        val p = LinkedHashMap<String, JsonValue>()
        p["id"] = JsonString(newId)
        p["name"] = JsonString(checkNotNull(name) { "name is required for $type" })
        p["type"] = JsonString(checkNotNull(type))
        priority?.let { p["priority"] = JsonNumber(it.toDouble()) }
        enabled?.let { p["enabled"] = JsonBool(it) }
        if ("new_cards_per_day" in m) p["new_cards_per_day"] = newPerDay?.let { JsonNumber(it.toDouble()) } ?: JsonNull
        if ("reviews_per_day" in m) p["reviews_per_day"] = reviewsPerDay?.let { JsonNumber(it.toDouble()) } ?: JsonNull
        settings?.takeIf { it.members.isNotEmpty() }?.let { p["settings"] = it }
        query?.let { p["query"] = JsonString(it) }
        WritePlan.Mint(listOf(DeckMints.created(JsonObject(p))), DeckMints::deckPointer) { after, _ ->
            201 to DeckViews.deck(after.learning, checkNotNull(DeckViews.deckRow(after.learning, newId)) { "deck $newId missing after deck.created" })
        }
    }
    return ShellResponse.json(deck, status = status)
}

/** The live deck [id] (`404` / `410`). */
private fun liveDeck(t: LearningTables, id: String): Row {
    val row = DeckViews.deckRow(t, id) ?: notFound()
    if (!DeckViews.isLive(row)) gone()
    return row
}

/**
 * `updateDeck`: one `deck.updated` with the registers present (`settings` partial, a `null` value removes the key).
 * Order as the server: body `422`s (unknown, immutable, empty, values; then the query must parse), `404` / `410`, then
 * `query` on a deck that is not smart (`422 /query`). A pulled delete beats this edit (L-6).
 */
internal fun updateDeck(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("deck_id", "deck")
    val body = objectBody(call)
    val m = body.members
    val e = FieldErrors()
    e.unknownKeys(body, PATCH_KEYS, PATCH_IMMUTABLE)
    if (m.isEmpty()) e.add("", "required", "At least one property is required.")
    val set = LinkedHashMap<String, JsonValue>()
    e.string(body, "name", 1, 200)?.let { set["name"] = JsonString(it) }
    m["priority"]?.let { v -> e.deckInt(v, "/priority", -1_000_000, 1_000_000)?.let { set["priority"] = JsonNumber(it.toDouble()) } }
    m["enabled"]?.let { v -> e.bool(v, "/enabled")?.let { set["enabled"] = JsonBool(it) } }
    for ((k, max) in listOf("new_cards_per_day" to 9999, "reviews_per_day" to 99999)) {
        val v = m[k] ?: continue
        if (v == JsonNull) set[k] = JsonNull else e.nullableInt(v, "/$k", max)?.let { set[k] = JsonNumber(it.toDouble()) }
    }
    val query = e.string(body, "query", 1, 2000)
    query?.let { set["query"] = JsonString(it) }
    m["settings"]?.let { v ->
        e.deckSettings(v, create = false)?.let { s ->
            if (s.members.isEmpty()) e.add("/settings", "required", "At least one settings key is required.") else set["settings"] = s
        }
    }
    e.throwIfAny()
    query?.let(::requireParses)

    val deck = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<JsonObject> { tx ->
        val row = liveDeck(tx.learning, id)
        if (query != null && DeckViews.type(row) != "smart") semantic("/query", "Only smart decks have a query.")
        WritePlan.Mint(listOf(DeckMints.updated(id, JsonObject(set))), DeckMints::deckPointer) { after, _ ->
            DeckViews.deck(after.learning, checkNotNull(DeckViews.deckRow(after.learning, id)) { "deck $id vanished" })
        }
    }
    return ShellResponse.json(deck)
}

/**
 * `deleteDeck`: one `deck.deleted` (items are kept and hidden with the deck). A media deck is `409 deck_read_only`
 * (delete the media); an already deleted deck answers `204` and mints nothing. Unknown `404`.
 */
internal fun deleteDeck(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("deck_id", "deck")
    call.services.get<LocalWrites<SqlLearningTables>>().exclusive<Unit> { tx ->
        val row = DeckViews.deckRow(tx.learning, id) ?: notFound()
        if (DeckViews.type(row) == "media") {
            throw ProblemException(Problems.conflict("deck_read_only", "Delete the media (DELETE /media/{media_id}); that tombstones its deck."))
        }
        if (!DeckViews.isLive(row)) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(listOf(DeckMints.deleted(id))) { _, _ -> }
    }
    return ShellResponse.noContent()
}
