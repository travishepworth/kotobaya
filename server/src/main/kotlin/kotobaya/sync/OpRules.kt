package kotobaya.sync

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType.*
import kotobaya.server.SERVER_DEVICE_ID
import org.jooq.DSLContext

/**
 * The per-op checks of SYNC.md 5.3 that need the op's meaning or the server's tables, in the
 * contract's order: [serverOnly] (pushed ops only), [references] (`unknown_reference`), then — after
 * the clock checks, which [OpEngine] does — [values] (`invalid_value`, then `invalid_target`, then
 * `immutable`). Each returns the first failure or null. Nothing here writes.
 *
 * Soft references (`entry_seq`, `sentence_id`, `source_*_id`, span `media_id`, `related_ids`) are
 * never checked (5.3). Tombstoned rows exist.
 */
internal class OpRules(private val dsl: DSLContext) {
    private fun exists(table: String, id: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from("app.$table").where("id = ?::uuid", id))

    private fun missing(what: String, id: String) = Rejection(RejectCode.UNKNOWN_REFERENCE, "$what $id does not exist on the server")

    private fun need(table: String, what: String, id: String?): Rejection? =
        if (id == null || exists(table, id)) null else missing(what, id)

    /** `server_only` for a pushed op: server-emitted types and server-only variants (5.3, OPS.md). */
    fun serverOnly(op: Op): Rejection? {
        val p = op.payload
        val why = when {
            op.opType.emitter == kotobaya.contracts.sync.Emitter.SERVER -> "${op.opType.wire} is a server-emitted op"
            op.deviceId == SERVER_DEVICE_ID -> "ops with the server device id are minted only by the server"
            op.opType == DECK_CREATED && p.str("type") in setOf("media", "list") -> "media and list decks are created only by the server"
            op.opType == REVIEW_LOGGED && (p.member("import_source") != null || p.member("replay_hlc") != null) ->
                "import_source and replay_hlc are set only by the server's history import"
            op.opType == WORD_STATUS_SET && p.str("origin") == "jpdb_import" -> "origin jpdb_import is set only by the server's import"
            else -> null
        }
        return why?.let { Rejection(RejectCode.SERVER_ONLY, it) }
    }

    fun references(op: Op): Rejection? {
        val p = op.payload
        return when (op.opType) {
            NOTE_TYPE_UPDATED, NOTE_TYPE_DELETED -> need("note_type", "note type", p.str("id"))
            NOTE_CREATED -> need("note_type", "note type", p.str("note_type_id")) ?: need("grammar_point", "grammar point", p.str("grammar_point_id"))
            NOTE_UPDATED, NOTE_DELETED -> need("note", "note", p.str("id"))
            NOTE_MERGED, CARD_MERGED -> {
                val t = if (op.opType == NOTE_MERGED) "note" else "card"
                need(t, t, p.str("loser_id")) ?: need(t, t, p.str("survivor_id"))
            }
            CARD_CREATED -> need("note", "note", p.str("note_id")) ?: need("fsrs_params", "preset", p.str("preset_id"))
            CARD_SUSPENDED, CARD_UNSUSPENDED, CARD_BURIED, CARD_UNBURIED, CARD_FLAG_SET, CARD_DELETED -> need("card", "card", p.str("id"))
            CARD_PRESET_SET -> need("card", "card", p.str("id")) ?: need("fsrs_params", "preset", p.str("preset_id"))
            REVIEW_LOGGED -> need("card", "card", p.str("card_id"))
            REVIEW_UNDONE -> need("review_log", "review", p.str("review_id"))
            NOTE_TYPE_CREATED, WORD_STATUS_SET, WORD_STATUS_CLEARED, DECK_CREATED, GRAMMAR_CREATED, SETTINGS_SET, FSRS_PARAMS_CREATED,
            MEDIA_CREATED, PARSE_OVERRIDE_PUT,
            -> null
            DECK_UPDATED, DECK_DELETED -> need("deck", "deck", p.str("id"))
            DECK_CONTENT_PUBLISHED -> need("deck", "deck", p.str("deck_id"))
            DECK_ITEM_PUT, DECK_ITEM_REMOVED -> need("deck", "deck", p.str("deck_id")) ?: run {
                val key = p.reqStr("item_key")
                if (key.startsWith("n:")) need("note", "note", key.substring(2)) else null
            }
            CONFUSABLE_SUGGESTED, CONFUSABLE_CONFIRMED, CONFUSABLE_REJECTED ->
                need("note", "note", p.str("note_a_id")) ?: need("note", "note", p.str("note_b_id"))
            GRAMMAR_UPDATED, GRAMMAR_DELETED -> need("grammar_point", "grammar point", p.str("id"))
            GRAMMAR_SPAN_PUT, GRAMMAR_SPAN_REMOVED -> need("grammar_point", "grammar point", p.str("grammar_point_id"))
            FSRS_PARAMS_UPDATED, FSRS_PARAMS_DELETED, FSRS_PARAMS_PUBLISHED -> need("fsrs_params", "preset", p.str("id"))
            MEDIA_UPDATED, MEDIA_DELETED -> need("media", "media", p.str("id"))
            MEDIA_CONTENT_PUBLISHED -> need("media", "media", p.str("media_id"))
            PARSE_OVERRIDE_DELETED -> need("parse_override", "parse override", p.str("id"))
        }
    }

    /**
     * `invalid_value`, then `invalid_target`, then `immutable` (5.3 order). [pushed] = the op came in
     * a push (the server's own ops skip the pushed-only rules, e.g. `deck.deleted` on a media deck,
     * and get the extra uniqueness guards of server-created media/list decks and media).
     */
    fun values(op: Op, pushed: Boolean): Rejection? =
        invalidValue(op, pushed)?.let { Rejection(RejectCode.INVALID_VALUE, it) }
            ?: invalidTarget(op, pushed)?.let { (msg, kind) -> Rejection(RejectCode.INVALID_TARGET, msg, kind) }
            ?: immutable(op)?.let { Rejection(RejectCode.IMMUTABLE, it) }

    private fun invalidValue(op: Op, pushed: Boolean): String? {
        val p = op.payload
        if (SyncJson.containsNul(p)) return "U+0000 cannot be stored by the server"
        return when (op.opType) {
            NOTE_TYPE_CREATED -> fieldsProblem(p.arr("fields"), p.str("base_type"))
            NOTE_TYPE_UPDATED -> p.obj("set")?.arr("fields")?.let { fieldsProblem(it, baseTypeOf(p.reqStr("id"))) }
            NOTE_CREATED -> {
                val refs = listOf("entry_seq" to "vocab", "kanji_literal" to "kanji", "grammar_point_id" to "grammar")
                    .filter { p.member(it.first) != null }
                val base = baseTypeOf(p.reqStr("note_type_id"))
                when {
                    refs.size > 1 -> "at most one of entry_seq, kanji_literal, grammar_point_id may be set"
                    refs.size == 1 && refs[0].second != base -> "${refs[0].first} on a $base note"
                    else -> null
                }
            }
            NOTE_MERGED, CARD_MERGED ->
                if (p.str("loser_id") == p.str("survivor_id")) "loser_id and survivor_id must differ" else null
            NOTE_UPDATED -> {
                val seq = p.obj("set")?.member("entry_seq")
                val type = dsl.fetchValue("select type from app.note where id = ?::uuid", p.reqStr("id")) as String?
                if (seq != null && type != "vocab") "entry_seq on a $type note" else null
            }
            DECK_CREATED -> when {
                p.str("type") == "media" && p.str("id") != p.str("media_id") -> "a media deck's id must equal its media_id"
                !pushed && p.str("type") == "media" && liveDeck("media_id = ?::uuid", p.reqStr("media_id")) ->
                    "a live deck for media ${p.str("media_id")} already exists"
                !pushed && p.str("type") == "list" && liveDeck("list_key = ?", p.reqStr("list_key")) ->
                    "a live deck for list ${p.str("list_key")} already exists"
                else -> int4("new_cards_per_day", p.member("new_cards_per_day")) ?: int4("reviews_per_day", p.member("reviews_per_day"))
            }
            DECK_UPDATED -> {
                val set = p.obj("set") ?: return null
                val type = dsl.fetchValue("select type from app.deck where id = ?::uuid", p.reqStr("id")) as String?
                if (set.has("query") && type != "smart") "query on a $type deck" else
                    int4("new_cards_per_day", set.member("new_cards_per_day")) ?: int4("reviews_per_day", set.member("reviews_per_day"))
            }
            CONFUSABLE_SUGGESTED, CONFUSABLE_CONFIRMED, CONFUSABLE_REJECTED ->
                if (p.reqStr("note_a_id") >= p.reqStr("note_b_id")) "note_a_id must be smaller than note_b_id" else null
            GRAMMAR_SPAN_PUT, GRAMMAR_SPAN_REMOVED ->
                if ((p.long("end_offset") ?: 0) <= (p.long("start_offset") ?: 0)) "end_offset must be greater than start_offset" else null
            FSRS_PARAMS_CREATED -> steps(p)
            FSRS_PARAMS_UPDATED -> p.obj("set")?.let(::steps)
            PARSE_OVERRIDE_PUT -> {
                val surfaces = p.arr("tokens")?.items.orEmpty().joinToString("") { ((it as? JsonObject)?.str("surface")).orEmpty() }
                if (surfaces != p.str("match_text")) "token surfaces do not concatenate to match_text" else null
            }
            MEDIA_CREATED -> {
                val jpdb = p.str("jpdb_id")
                if (!pushed && jpdb != null && dsl.fetchExists(
                        dsl.selectOne().from("app.media").where("jpdb_id = ? and deleted_at is null", jpdb),
                    )
                ) "a live media for jpdb deck $jpdb already exists" else null
            }
            else -> null
        }
    }

    /** The message and the target deck's `type`. */
    private fun invalidTarget(op: Op, pushed: Boolean): Pair<String, String?>? {
        val p = op.payload
        return when (op.opType) {
            DECK_DELETED -> if (pushed && deckType(p.reqStr("id")) == "media") "delete the media instead of its deck" to "media" else null
            DECK_ITEM_PUT, DECK_ITEM_REMOVED -> deckType(p.reqStr("deck_id")).let { t ->
                when (t) {
                    "user" -> null
                    "smart" -> "a smart deck has no items" to t
                    else -> "items of a $t deck are server-built" to t
                }
            }
            else -> null
        }
    }

    private fun immutable(op: Op): String? {
        val p = op.payload
        return when (op.opType) {
            NOTE_TYPE_DELETED -> if (isBuiltinType(p.reqStr("id"))) "built-in note types cannot be deleted" else null
            NOTE_TYPE_UPDATED -> {
                val seeded = BUILTIN_SEEDS[p.reqStr("id")] ?: return null
                val set = p.obj("set") ?: return null
                val keys = set.arr("fields")?.items?.mapNotNull { (it as? JsonObject)?.str("key") }?.toSet()
                val removedField = keys != null && !keys.containsAll(seeded.first)
                val removedTemplate = set.obj("templates")?.members?.any { (k, v) -> v == JsonNull && k in seeded.second } == true
                when {
                    removedField -> "a built-in note type's seeded field keys cannot be removed"
                    removedTemplate -> "a built-in note type's seeded templates cannot be removed"
                    else -> null
                }
            }
            FSRS_PARAMS_DELETED -> if (p.str("id") == Reserved.DEFAULT_PRESET_ID) "the Default preset cannot be deleted" else null
            else -> null
        }
    }

    private fun liveDeck(cond: String, v: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from("app.deck").where("$cond and deleted_at is null", v))

    private fun deckType(id: String): String? = dsl.fetchValue("select type from app.deck where id = ?::uuid", id) as String?

    private fun baseTypeOf(noteTypeId: String): String? =
        dsl.fetchValue("select base_type from app.note_type where id = ?::uuid", noteTypeId) as String?

    private fun isBuiltinType(id: String): Boolean =
        dsl.fetchValue("select builtin from app.note_type where id = ?::uuid", id) as Boolean? == true

    private fun fieldsProblem(fields: JsonArray?, baseType: String?): String? {
        val keys = fields?.items?.mapNotNull { (it as? JsonObject)?.str("key") }.orEmpty()
        if (keys.size != keys.toSet().size) return "duplicate key in fields"
        if (baseType == "vocab" && !keys.containsAll(VOCAB_KEYS)) return "a vocab note type needs the field keys expression, reading and meaning"
        return null
    }

    private fun int4(name: String, v: kotobaya.contracts.sync.JsonValue?): String? =
        if (v is JsonNumber && v.value > Int.MAX_VALUE) "$name is larger than ${Int.MAX_VALUE}" else null

    private fun steps(o: JsonObject): String? = listOf("learning_steps_s", "relearning_steps_s").firstNotNullOfOrNull { k ->
        if (o.arr(k)?.items?.any { it is JsonNumber && it.value > Int.MAX_VALUE } == true) "$k has a step larger than ${Int.MAX_VALUE}" else null
    }

    companion object {
        val VOCAB_KEYS = listOf("expression", "reading", "meaning")

        /** Built-in note types (V1 seed): id → (seeded field keys, seeded template kinds). */
        val BUILTIN_SEEDS: Map<String, Pair<Set<String>, Set<String>>> = mapOf(
            "00000000-0000-7000-8000-000000000101" to (
                setOf("expression", "reading", "meaning", "sentence", "sentence_translation", "audio", "sentence_audio", "image", "notes") to
                    setOf("meaning", "reading", "listening", "production", "pitch")
                ),
            "00000000-0000-7000-8000-000000000102" to (setOf("literal", "keyword", "readings", "notes") to setOf("keyword", "writing", "components")),
            "00000000-0000-7000-8000-000000000103" to (setOf("expression", "reading", "meaning", "audio", "notes") to setOf("meaning", "production", "listening")),
            "00000000-0000-7000-8000-000000000104" to (setOf("pattern", "meaning", "notes") to setOf("cloze", "recognition", "spot", "production")),
            "00000000-0000-7000-8000-000000000105" to (
                setOf("sentence", "reading", "translation", "audio", "image", "notes") to setOf("comprehension", "listening")
                ),
            "00000000-0000-7000-8000-000000000106" to (setOf("front", "back") to setOf("basic")),
        )
    }
}
