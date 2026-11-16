package kotobaya.android.shell.api.decks

import kotobaya.android.shell.api.FieldErrors
import kotobaya.android.shell.api.actions.KEY_RE
import kotobaya.android.shell.api.actions.integral
import kotobaya.android.shell.api.actions.jsonObj
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRefusal
import kotobaya.sync.client.mint.MintRequest

/**
 * The deck mint requests (OPS.md `deck.*`), copied from the native writers (`DeckMembership.set` / `createDeck`, used
 * by `DeckWordOps`, `SearchDeckOps` and the Decks screen) so the shell and the native app mint the same payloads.
 */
internal object DeckMints {
    fun created(payload: JsonObject): MintRequest = MintRequest(OpType.DECK_CREATED, payload)

    fun updated(id: String, set: JsonObject): MintRequest = MintRequest(OpType.DECK_UPDATED, jsonObj("id" to JsonString(id), "set" to set))

    fun deleted(id: String): MintRequest = MintRequest(OpType.DECK_DELETED, jsonObj("id" to JsonString(id)))

    fun itemPut(deckId: String, key: String, position: Long): MintRequest = MintRequest(
        OpType.DECK_ITEM_PUT,
        jsonObj("deck_id" to JsonString(deckId), "item_key" to JsonString(key), "position" to JsonNumber(position.toDouble())),
    )

    fun itemRemoved(deckId: String, key: String): MintRequest =
        MintRequest(OpType.DECK_ITEM_REMOVED, jsonObj("deck_id" to JsonString(deckId), "item_key" to JsonString(key)))

    /** A refused `deck.created` / `deck.updated` pointer as the request property (`/payload/set/name` → `/name`). */
    fun deckPointer(r: MintRefusal.Invalid): String {
        val p = r.pointer.removePrefix("/payload")
        return when {
            p.startsWith("/set/") -> p.removePrefix("/set")
            p.startsWith("/id") || p == "/set" -> ""
            else -> p
        }
    }
}

/** Server `DeckItemApi`: the step between appended positions. */
internal const val POSITION_STEP: Long = 1024L

/** Server `MAX_POSITION` (2^53 − 1). */
internal const val MAX_POSITION: Long = 9_007_199_254_740_991L

/** `e:<seq>` (at most 16 digits) or `n:<lowercase uuid>`, exactly as the server's `ITEM_KEY`. */
internal val ITEM_KEY = Regex("^(e:[1-9][0-9]{0,15}|n:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")

internal val SETTING_ORDERS = setOf("deck_frequency", "global_frequency", "position", "chronological")
internal val SETTING_ADD_KANJI = setOf("off", "before", "alongside")

/** An integer in `[min, max]` (server `DeckApi` `Errors.int`): not an integer `invalid_type`, outside `invalid_value`. */
internal fun FieldErrors.deckInt(v: JsonValue, at: String, min: Int, max: Int): Int? {
    val n = integral(v)
    if (n == null) {
        add(at, "invalid_type", "Must be an integer.")
        return null
    }
    if (n < min || n > max) {
        add(at, "invalid_value", "Must be between $min and $max.")
        return null
    }
    return n.toInt()
}

/** A position within ±(2^53 − 1); anything else `invalid_value` at [at] (server `DeckItemApi`). */
internal fun FieldErrors.position(v: JsonValue, at: String): Long? {
    val n = integral(v)?.takeIf { it in -MAX_POSITION..MAX_POSITION }
    if (n == null) add(at, "invalid_value", "An integer within ±$MAX_POSITION.")
    return n
}

/**
 * A deck `settings` object (server `Errors.settings`): keys are lowercase identifiers, known keys are checked. On
 * create a null value is dropped; on update it is kept (it removes the key).
 */
internal fun FieldErrors.deckSettings(v: JsonValue, create: Boolean): JsonObject? {
    val members = (v as? JsonObject)?.members
    if (members == null) {
        add("/settings", "invalid_type", "Must be an object.")
        return null
    }
    val out = LinkedHashMap<String, JsonValue>()
    for ((k, x) in members) {
        val at = "/settings/$k"
        if (!KEY_RE.matches(k) || k.length > 64) {
            add(at, "invalid_value", "A settings key is a lowercase identifier of at most 64 characters.")
            continue
        }
        if (x == JsonNull) {
            if (!create) out[k] = JsonNull
            continue
        }
        val ok = when (k) {
            "order" -> (x as? JsonString)?.value in SETTING_ORDERS
            "add_kanji" -> (x as? JsonString)?.value in SETTING_ADD_KANJI
            "card_kinds" -> x is JsonArray && x.items.all { (it as? JsonString)?.value?.let(KEY_RE::matches) == true }
            else -> true
        }
        if (!ok) add(at, "invalid_value", "Not a valid value for $k.") else out[k] = x
    }
    return JsonObject(out)
}
