package kotobaya.android.shell.api.decks

import java.util.Base64
import kotobaya.android.dict.DictLookup
import kotobaya.android.dict.Resolved
import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.LocalCall
import kotobaya.android.shell.api.LocalWrites
import kotobaya.android.shell.api.ProblemException
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.WritePlan
import kotobaya.android.shell.api.actions.CodePointOrder
import kotobaya.android.shell.api.actions.exclusive
import kotobaya.android.shell.api.actions.integral
import kotobaya.android.shell.api.actions.isNullish
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.android.shell.api.actions.pageLimit
import kotobaya.android.shell.api.actions.pathId
import kotobaya.android.shell.api.actions.text
import kotobaya.android.shell.api.actions.unknownKeys
import kotobaya.android.shell.api.actions.uuidValue
import kotobaya.android.shell.api.get
import kotobaya.android.shell.api.mining.DictViews
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.MergeReaders
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRefusal

private val STATUSES = setOf("new", "learning", "known", "never_forget", "blacklisted", "ignored")
private val SORTS = listOf("position", "occurrence_count", "freq_rank")

private fun semantic(pointer: String, detail: String, code: String = "invalid_value"): Nothing =
    throw ProblemException(Problems.validationFailed(pointer, code, detail))

private fun notApplicable(): Nothing =
    throw ProblemException(Problems.conflict("not_applicable", "A smart deck has no items; use GET /cards?query=."))

/** `404` / `410` / `409` for a deck that cannot take item writes (server `requireUserDeck`). */
private fun requireUserDeck(t: LearningTables, id: String) {
    val deck = DeckViews.deckRow(t, id) ?: throw ProblemException(Problems.notFound("No such deck."))
    if (!DeckViews.isLive(deck)) throw ProblemException(Problems.gone("The deck was deleted."))
    when (val type = DeckViews.type(deck)) {
        "smart" -> notApplicable()
        "media", "list" -> throw ProblemException(Problems.conflict("deck_read_only", "Items of a $type deck are server-built."))
    }
}

/** A path `item_key` (server `itemKey()`): not `e:<seq>` / `n:<uuid>` → `422 /item_key`. */
private fun pathItemKey(call: LocalCall): String =
    call.path("item_key").takeIf(ITEM_KEY::matches) ?: semantic("/item_key", "Must be e:<entry_seq> or n:<lowercase note uuid>.")

/** The survivor of [noteId] (one `merged_into_id` hop), or null when the note does not exist. */
private fun survivor(t: LearningTables, noteId: String): Row? = MergeReaders.resolveNote(t, noteId)

private fun Row.entrySeq(): Long? = integral(this["entry_seq"])

/**
 * Whether entry [seq] exists, by the L-7 lookup as `createNote` / `updateNote` check an `entry_seq`: installed package,
 * sidecar cache, then (online) the server. An authoritative miss is the server's `422` at [pointer]; a miss the device
 * cannot decide is `503 shell_dict_missing` (nothing minted). Runs before the write transaction (it may suspend).
 */
private suspend fun requireEntry(dict: DictLookup, seq: Long, pointer: String) {
    when (dict.resolveEntry(seq)) {
        is Resolved.Found -> Unit
        Resolved.NotFound -> semantic(pointer, "No such dictionary entry.")
        Resolved.NotOnDevice -> throw ProblemException(Problems.shellDictMissing("Dictionary entry $seq is not on this device."))
    }
}

/** One item of a page, positioned by its sort key (`null` last) and then its identity. */
private class ItemPos(val value: Double?, val identity: String)

private val itemOrder: Comparator<ItemPos> = Comparator { a, b ->
    val av = a.value
    val bv = b.value
    val byKey = when {
        av == null && bv == null -> 0
        av == null -> 1
        bv == null -> -1
        else -> av.compareTo(bv)
    }
    if (byKey != 0) byKey else CodePointOrder.compare(a.identity, b.identity)
}

private fun encodeCursor(p: ItemPos, hash: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${p.value?.toString().orEmpty()}|${p.identity}|$hash".toByteArray())

private fun decodeCursor(raw: String, hash: String): ItemPos? {
    val parts = runCatching { String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).split('|') }.getOrNull() ?: return null
    if (parts.size != 3 || parts[2] != hash || !ITEM_KEY.matches(parts[1])) return null
    val value = if (parts[0].isEmpty()) null else parts[0].toDoubleOrNull() ?: return null
    return ItemPos(value, parts[1])
}

/**
 * `listDeckItems`: the visible items (SYNC.md 8.5) of a user, list or media deck, filtered by effective word status
 * (entry items only) and sorted as the server (`position`; `occurrence_count` most first; `freq_rank` most frequent
 * first, NULL last; ties by identity), paged with an opaque keyset cursor `(sort value, identity)`. `entry` is the
 * dictionary summary by L-7 steps 1-2 (null on a miss). Smart deck `409 not_applicable`, tombstone `410`.
 */
internal fun listDeckItems(call: LocalCall): ShellResponse {
    val q = call.query("status", "sort", "limit", "cursor")
    val id = call.pathId("deck_id", "deck")
    val e = FieldErrors()
    val statuses = q.first("status")?.let { v ->
        val all = v.split(',')
        if (all.all { it in STATUSES }) all.toSet() else null.also { e.add("/status", "invalid_value", "Comma-separated: ${STATUSES.joinToString(", ")}.") }
    }
    val sort = q.first("sort")?.let { v -> v.takeIf { it in SORTS } ?: run { e.add("/sort", "invalid_value", "Must be one of ${SORTS.joinToString(", ")}."); null } }
        ?: "position"
    val limit = pageLimit(q.first("limit"), e)
    val hash = ((statuses?.sorted() ?: emptyList()).joinToString(",") + "|" + sort + "|" + id).hashCode().toString(16)
    val after = q.first("cursor")?.let { c -> decodeCursor(c, hash) ?: null.also { e.add("/cursor", "invalid_value", "Malformed or foreign cursor.") } }
    e.throwIfAny()

    val dict = call.services.get<DictLookup>()
    val body = call.services.get<SqlDelightDeviceStore>().read { tx ->
        val t = tx.learning
        val deck = DeckViews.deckRow(t, id) ?: throw ProblemException(Problems.notFound("No such deck."))
        if (!DeckViews.isLive(deck)) throw ProblemException(Problems.gone("The deck was deleted."))
        if (DeckViews.type(deck) == "smart") notApplicable()
        val visible = DeckViews.visibleItems(t, id).filter { item ->
            statuses == null || item.entrySeq?.let { DictViews.status(t, it) in statuses } == true
        }
        val byIdentity = visible.associateBy { it.identity }
        val positions = visible.map { item ->
            val value = when (sort) {
                "occurrence_count" -> -item.occurrenceCount.toDouble()
                "freq_rank" -> item.entrySeq?.let { s -> runCatching { dict.entry(s) }.getOrNull()?.freqRank?.toDouble() }
                else -> item.position.toDouble()
            }
            ItemPos(value, item.identity)
        }
        val window = positions.sortedWith(itemOrder).asSequence()
            .filter { after == null || itemOrder.compare(it, after) > 0 }
            .take(limit + 1).toList()
        val shown = window.take(limit)
        jsonObj(
            "items" to JsonArray(shown.map { DeckViews.item(t, dict, id, checkNotNull(byIdentity[it.identity])) }),
            "next_cursor" to if (window.size > limit) JsonString(encodeCursor(shown.last(), hash)) else JsonNull,
        )
    }
    return ShellResponse.json(body)
}

/** One validated element of `addDeckItems`. */
private class Element(val entrySeq: Long?, val noteId: String?, val position: Long?)

/** A refused `deck.item_put` pointer: the server names the whole array (`{ it }` of its `rest` call maps nothing). */
private fun itemsPointer(r: MintRefusal.Invalid): String = if (r.pointer.endsWith("/position")) "/items" else ""

/**
 * `addDeckItems`: one `deck.item_put` per distinct key in array order (a key repeated in the array: the last element
 * wins and takes its slot), all or nothing. A `note_id` is resolved to its survivor and stored as `e:<seq>` when that
 * note has an entry. Elements without `position` are appended after the current maximum, 1024 apart. An item already
 * present is put again (L-2: a put that changes nothing still mints), never a second row. The answer lists the items
 * as stored, in request order.
 */
internal suspend fun addDeckItems(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("deck_id", "deck")
    val body = objectBody(call)
    val e = FieldErrors()
    e.unknownKeys(body, setOf("items"))
    val node = body.members["items"]
    val arr = (node as? JsonArray)?.items
    when {
        node == null -> e.add("/items", "required", "items must be an array.")
        arr == null -> e.add("/items", "invalid_type", "items must be an array.")
        arr.isEmpty() || arr.size > 1000 -> e.add("/items", "out_of_range", "Between 1 and 1000 items.")
    }
    e.throwIfAny()
    val elements = checkNotNull(arr).mapIndexed { i, v ->
        val o = v as? JsonObject ?: return@mapIndexed null.also { e.add("/items/$i", "invalid_type", "Must be an object.") }
        e.unknownKeys(o, setOf("entry_seq", "note_id", "position"))
        val hasE = "entry_seq" in o.members
        val hasN = "note_id" in o.members
        if (hasE == hasN) return@mapIndexed null.also { e.add("/items/$i", "invalid_value", "Exactly one of entry_seq and note_id.") }
        val seq = o.members["entry_seq"]?.let { s ->
            integral(s)?.takeIf { it >= 1 && it.toString().length <= 16 } ?: null.also { e.add("/items/$i/entry_seq", "invalid_value", "A positive entry_seq.") }
        }
        val note = o.members["note_id"]?.let { e.uuidValue(it, "/items/$i/note_id") }
        val pos = o.members["position"]?.let { e.position(it, "/items/$i/position") }
        Element(seq, note, pos)
    }
    e.throwIfAny()

    val store = call.services.get<SqlDelightDeviceStore>()
    // 404 / 410 / 409 come before an element's 422 (the server checks the deck first, in its transaction)
    store.read { requireUserDeck(it.learning, id) }
    val dict = call.services.get<DictLookup>()
    elements.forEachIndexed { i, el -> el?.entrySeq?.let { requireEntry(dict, it, "/items/$i/entry_seq") } }

    val items = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<List<JsonObject>> { tx ->
        val t = tx.learning
        requireUserDeck(t, id)
        val wanted = LinkedHashMap<String, Long?>()
        elements.forEachIndexed { i, el ->
            checkNotNull(el)
            val key = if (el.entrySeq != null) {
                "e:${el.entrySeq}"
            } else {
                val s = survivor(t, checkNotNull(el.noteId)) ?: semantic("/items/$i/note_id", "No such note.")
                if (!s["deleted_at"].isNullish()) semantic("/items/$i/note_id", "The note is deleted.")
                s.entrySeq()?.let { "e:$it" } ?: "n:${s["id"].text()}"
            }
            wanted.remove(key)
            wanted[key] = el.position
        }
        var next = DeckViews.maxPosition(t, id)
        val requests = wanted.entries.map { (key, pos) ->
            val position = pos ?: (next + POSITION_STEP).also { next = it }
            if (position !in -MAX_POSITION..MAX_POSITION) semantic("/items", "Position out of range.")
            DeckMints.itemPut(id, key, position)
        }
        WritePlan.Mint(requests, ::itemsPointer) { after, _ ->
            wanted.keys.map { k -> DeckViews.item(after.learning, dict, id, checkNotNull(DeckViews.visibleItem(after.learning, id, k)) { "item $k missing" }) }
        }
    }
    return ShellResponse.json(jsonObj("items" to JsonArray(items)))
}

/**
 * `putDeckItem`: one `deck.item_put`. Without `position` a new item is appended (maximum + 1024) and an existing live
 * item keeps its position. An `n:` key names a note (its survivor) without an entry; one whose note has an entry is
 * `422` (use the `e:` form). The entry or note must exist.
 */
internal suspend fun putDeckItem(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("deck_id", "deck")
    val key = pathItemKey(call)
    var position: Long? = null
    if (!call.request.body.isNullOrBlank()) {
        val body = objectBody(call)
        val e = FieldErrors()
        e.unknownKeys(body, setOf("position"))
        body.members["position"]?.let { v -> position = e.position(v, "/position") }
        e.throwIfAny()
    }
    val store = call.services.get<SqlDelightDeviceStore>()
    store.read { requireUserDeck(it.learning, id) }
    val dict = call.services.get<DictLookup>()
    if (key.startsWith("e:")) requireEntry(dict, key.substring(2).toLong(), "/item_key")

    val item = call.services.get<LocalWrites<SqlLearningTables>>().exclusive<JsonObject> { tx ->
        val t = tx.learning
        requireUserDeck(t, id)
        val canonical = if (key.startsWith("e:")) {
            key
        } else {
            val s = survivor(t, key.substring(2)) ?: semantic("/item_key", "No such note.")
            if (!s["deleted_at"].isNullish()) semantic("/item_key", "The note is deleted.")
            s.entrySeq()?.let { semantic("/item_key", "The note has an entry; use the key e:$it.") }
            "n:${s["id"].text()}"
        }
        val pos = position ?: DeckViews.livePosition(t, id, canonical) ?: (DeckViews.maxPosition(t, id) + POSITION_STEP)
        if (pos !in -MAX_POSITION..MAX_POSITION) semantic("/position", "Position out of range.")
        WritePlan.Mint(listOf(DeckMints.itemPut(id, canonical, pos)), { "/position" }) { after, _ ->
            DeckViews.item(after.learning, dict, id, checkNotNull(DeckViews.visibleItem(after.learning, id, canonical)) { "item $canonical missing" })
        }
    }
    return ShellResponse.json(item)
}

/**
 * `removeDeckItem`: one `deck.item_removed` for every live key of the deck with the identity the user sees (SYNC.md
 * 8.5 client duty: `n:<loser>` keys and the `e:<seq>` key of the same word), in one transaction. Item absent or
 * already removed: `204`, nothing minted.
 */
internal fun removeDeckItem(call: LocalCall): ShellResponse {
    call.query()
    val id = call.pathId("deck_id", "deck")
    val key = pathItemKey(call)
    call.services.get<LocalWrites<SqlLearningTables>>().exclusive<Unit> { tx ->
        val t = tx.learning
        requireUserDeck(t, id)
        val identity = if (key.startsWith("e:")) {
            key
        } else {
            val s = survivor(t, key.substring(2))
            when {
                s == null -> key
                s.entrySeq() != null -> "e:${s.entrySeq()}"
                else -> "n:${s["id"].text()}"
            }
        }
        val keys = DeckViews.liveKeysOf(t, id, identity)
        if (keys.isEmpty()) return@exclusive WritePlan.Answer(Unit)
        WritePlan.Mint(keys.map { DeckMints.itemRemoved(id, it) }) { _, _ -> }
    }
    return ShellResponse.noContent()
}
