package kotobaya.notes

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotobaya.contracts.cards.CardGenerator
import kotobaya.contracts.sync.OpType
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Problems
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.server.respondJson
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

private const val MAX_TAGS_PER_NOTE = 200
private const val DECK_POSITION_STEP = 1024L
private val CREATE_KEYS = setOf(
    "id", "note_type_id", "entry_seq", "kanji_literal", "grammar_point_id", "fields", "tags", "mnemonic",
    "source_sentence", "source_sentence_id", "source_media_id", "origin", "card_kinds", "deck_id",
)
private val PATCH_KEYS = setOf(
    "fields", "tags", "add_tags", "remove_tags", "mnemonic", "source_sentence", "source_sentence_id", "source_media_id", "entry_seq",
)
private val PATCH_IMMUTABLE = setOf("note_type_id", "kanji_literal", "grammar_point_id", "origin")

/** `listNotes`, `createNote`, `getNote`, `updateNote`, `deleteNote`. */
internal class NoteApi(
    private val db: Database,
    private val opApply: OpApply,
    private val cards: CardGenerator,
    private val basePath: String,
) {
    suspend fun list(call: ApplicationCall) {
        val q = call.request.queryParameters
        val e = Errors()
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        val includeDeleted = call.includeDeleted()
        if (!includeDeleted) where += "n.deleted_at is null"
        q["q"]?.let { v ->
            if (e.stringValue(json.nodeFactory.textNode(v), "/q", 1, 200) != null) {
                where += "n.search_text ilike ? escape '!'"
                args += "%" + v.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
            }
        }
        q["type"]?.let { v ->
            if (v in BASE_TYPES) { where += "n.type = ?"; args += v } else e.add("/type", "invalid_value", "Must be one of ${BASE_TYPES.joinToString(", ")}.")
        }
        for ((name, col) in listOf("note_type_id" to "n.note_type_id", "grammar_point_id" to "n.grammar_point_id")) {
            q[name]?.let { v ->
                if (UUID_RE.matches(v)) { where += "$col = ?::uuid"; args += v } else e.add("/$name", "invalid_value", "Must be a lowercase UUID.")
            }
        }
        q["tag"]?.let { v ->
            when {
                v.isEmpty() -> e.add("/tag", "invalid_value", "Must not be empty.")
                v == "leech" -> e.add("/tag", "invalid_value", "The virtual tag leech is not accepted here; use GET /cards?query=tag:leech.")
                else -> { where += "? = any(n.tags)"; args += v }
            }
        }
        q["entry_seq"]?.let { v ->
            val n = v.toLongOrNull()
            if (n != null && n >= 1) { where += "n.entry_seq = ?"; args += n } else e.add("/entry_seq", "invalid_value", "Must be an integer of at least 1.")
        }
        q["kanji_literal"]?.let { v ->
            if (v.isNotEmpty() && v.codePointCount(0, v.length) <= 2) { where += "n.kanji_literal = ?"; args += v } else e.add("/kanji_literal", "invalid_value", "One kanji.")
        }
        q["updated_since"]?.let { v ->
            try {
                OffsetDateTime.parse(v)
                where += "n.updated_at > ?::timestamptz"; args += v
            } catch (x: DateTimeParseException) {
                e.add("/updated_since", "invalid_value", "Must be an RFC 3339 instant.")
            }
        }
        val limit = q["limit"]?.let { v ->
            v.toIntOrNull()?.takeIf { it in 1..500 } ?: run { e.add("/limit", "out_of_range", "Must be 1 to 500."); null }
        } ?: 50
        val filterHash = (where + args.map { it.toString() }).hashCode().toString(16)
        q["cursor"]?.let { c ->
            val parts = runCatching { String(Base64.getUrlDecoder().decode(c), Charsets.UTF_8).split('|') }.getOrNull()
            if (parts == null || parts.size != 3 || parts[2] != filterHash || !UUID_RE.matches(parts[1])) {
                e.add("/cursor", "invalid_value", "Malformed or foreign cursor.")
            } else {
                where += "(n.created_at, n.id) < (?::timestamptz, ?::uuid)"
                args += parts[0]; args += parts[1]
            }
        }
        e.throwIfAny()

        val page = db.tx { dsl ->
            val sql = NoteViews.NOTE_SELECT + (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
                " order by n.created_at desc, n.id desc limit ${limit + 1}"
            val rows = dsl.fetch(sql, *args.toTypedArray())
            val more = rows.size > limit
            val shown = rows.take(limit)
            val notes = NoteViews(dsl).withLiveCards(NoteViews.parseNotes(shown.map { it.get(0, String::class.java) }))
            val next = if (more) {
                shown.last().let { Base64.getUrlEncoder().withoutPadding().encodeToString("${it.get(1, String::class.java)}|${it.get(2, String::class.java)}|$filterHash".toByteArray()) }
            } else {
                null
            }
            mapOf("items" to notes, "next_cursor" to next)
        }
        call.respondJson(page)
    }

    suspend fun get(call: ApplicationCall) {
        val id = call.pathId("note_id")
        val deleted = call.includeDeleted()
        val note = db.tx { dsl ->
            val views = NoteViews(dsl)
            val row = views.resolve(id) ?: throw Problems.notFound("No such note.")
            if (!deleted && !row["deleted_at"].isNull) throw gone("note", row["deleted_at"].asText())
            views.note(row["id"].asText())
        } ?: throw Problems.notFound("No such note.")
        if (note["id"].asText() != id) call.response.headers.append("Content-Location", "$basePath/notes/${note["id"].asText()}")
        call.respondJson(note)
    }

    suspend fun create(call: ApplicationCall) {
        val body = call.jsonBody()
        val onDuplicate = call.request.queryParameters["on_duplicate"] ?: "error"
        val e = Errors()
        if (onDuplicate !in setOf("error", "return_existing")) e.add("/on_duplicate", "invalid_value", "Must be error or return_existing.")
        e.unknownKeys(body, CREATE_KEYS)
        val id = e.uuid(body, "id")
        val noteTypeId = e.uuid(body, "note_type_id", required = true)
        val entrySeq = body.get("entry_seq")?.let { e.long(it, "/entry_seq", 1) }
        val kanji = e.string(body, "kanji_literal", 1, 2)?.also {
            if (it.codePointCount(0, it.length) != 1) e.add("/kanji_literal", "invalid_value", "Exactly one kanji (one code point).")
        }
        val grammarId = e.uuid(body, "grammar_point_id")
        val fields = body.get("fields")?.let { e.fieldValues(it, "/fields", 1) } ?: json.createObjectNode()
        val tags = body.get("tags")?.let { e.tagList(it, "/tags", 0, 100) } ?: emptyList()
        val mnemonic = e.string(body, "mnemonic", 0, 10_000) ?: ""
        val sourceSentence = e.string(body, "source_sentence", 0, 10_000) ?: ""
        val sourceSentenceId = e.uuid(body, "source_sentence_id")
        val sourceMediaId = e.uuid(body, "source_media_id")
        val origin = e.string(body, "origin", 1, 40)?.also {
            if (!KEY_RE.matches(it)) e.add("/origin", "invalid_value", "A lowercase identifier.")
        } ?: "api"
        val cardKinds = body.get("card_kinds")?.let { e.kindList(it) }
        val deckId = e.uuid(body, "deck_id")
        val refs = listOf("entry_seq" to (entrySeq != null), "kanji_literal" to (kanji != null), "grammar_point_id" to (grammarId != null)).filter { it.second }
        if (refs.size > 1) refs.forEach { e.add("/${it.first}", "conflicting_fields", "At most one of entry_seq, kanji_literal and grammar_point_id.") }
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val views = NoteViews(tx.dsl)
            val noteId = id ?: Uuid7.generate().toString()
            // Idempotent retry (live, deleted or merged): the note or its survivor, nothing emitted.
            views.resolve(noteId)?.let { return@rest Reply(HttpStatusCode.OK, views.note(it["id"].asText())) }

            val type = views.noteType(noteTypeId ?: error("validated")) ?: semantic("/note_type_id", "not_found", "No such note type.")
            if (!type["deleted_at"].isNull) semantic("/note_type_id", "invalid_value", "The note type is deleted.")
            val base = type["base_type"].asText()
            val se = Errors()
            for ((name, given, fits) in listOf(
                Triple("entry_seq", entrySeq != null, "vocab"),
                Triple("kanji_literal", kanji != null, "kanji"),
                Triple("grammar_point_id", grammarId != null, "grammar"),
            )) {
                if (given && base != fits) se.add("/$name", "invalid_value", "$name is for $fits note types, not $base.")
            }
            if (entrySeq != null && !tx.dsl.fetchExists(tx.dsl.selectOne().from("ref.dict_entry").where("seq = ?", entrySeq))) {
                se.add("/entry_seq", "not_found", "No dictionary entry $entrySeq.")
            }
            if (grammarId != null && !tx.dsl.fetchExists(tx.dsl.selectOne().from("app.grammar_point").where("id = ?::uuid and deleted_at is null", grammarId))) {
                se.add("/grammar_point_id", "not_found", "No live grammar point $grammarId.")
            }
            val info = type.toNoteTypeInfo()
            cardKinds?.forEachIndexed { i, k -> if (info.templates.none { it.kind == k }) se.add("/card_kinds/$i", "invalid_value", "The note type has no card kind $k.") }
            se.throwIfAny()
            if (deckId != null && !isLiveUserDeck(tx, deckId)) {
                throw ApiException(HttpStatusCode.Conflict, "deck_read_only", "Deck is read-only", "The deck is not a live user deck.")
            }

            val (col, value) = when {
                entrySeq != null -> "entry_seq" to (entrySeq as Any)
                kanji != null -> "kanji_literal" to (kanji as Any)
                grammarId != null -> "grammar_point_id" to (grammarId as Any)
                else -> null to null
            }
            val holder = if (col != null && value != null) views.liveHolder(col, value) else null
            if (holder != null) {
                if (onDuplicate == "error") {
                    throw ApiException(
                        HttpStatusCode.Conflict, "already_exists", "Already exists", "A live note already references $col $value.",
                        mapOf("existing_id" to holder),
                    )
                }
                deckId?.let { putDeckItem(tx, it, entrySeq, holder) }
                return@rest Reply(HttpStatusCode.OK, views.note(holder), tx.lastSeq)
            }

            val p = json.createObjectNode()
            p.put("id", noteId).put("note_type_id", noteTypeId)
            entrySeq?.let { p.put("entry_seq", it) }
            kanji?.let { p.put("kanji_literal", it) }
            grammarId?.let { p.put("grammar_point_id", it) }
            p.set<JsonNode>("fields", fields)
            p.set<JsonNode>("tags", json.createArrayNode().also { a -> tags.forEach(a::add) })
            p.put("mnemonic", mnemonic).put("source_sentence", sourceSentence)
            sourceSentenceId?.let { p.put("source_sentence_id", it) }
            sourceMediaId?.let { p.put("source_media_id", it) }
            p.put("origin", origin)
            tx.emit(OpType.NOTE_CREATED, payload(p))
            for (kind in cards.kindsForNewNote(info, cardKinds)) emitCard(tx, noteId, kind)
            deckId?.let { putDeckItem(tx, it, entrySeq, noteId) }
            Reply(HttpStatusCode.Created, views.note(noteId) ?: error("note $noteId missing after note.created"), tx.lastSeq)
        }
        call.send(reply)
    }

    suspend fun update(call: ApplicationCall) {
        val id = call.pathId("note_id")
        val body = call.jsonBody()
        val e = Errors()
        e.unknownKeys(body, PATCH_KEYS, PATCH_IMMUTABLE)
        if (body.isEmpty) e.add("", "required", "At least one property is required.")
        val fields = body.get("fields")?.let { e.fieldValues(it, "/fields", 1) }
        val tags = body.get("tags")?.let { e.tagList(it, "/tags", 0, 100) }
        val addTags = body.get("add_tags")?.let { e.tagList(it, "/add_tags", 1, 100) }
        val removeTags = body.get("remove_tags")?.let { e.tagList(it, "/remove_tags", 1, 100) }
        if (tags != null && (addTags != null || removeTags != null)) {
            e.add("/tags", "conflicting_fields", "tags cannot be combined with add_tags or remove_tags.")
        }
        val mnemonic = e.string(body, "mnemonic", 0, 10_000)
        val sourceSentence = e.string(body, "source_sentence", 0, 10_000)
        val sourceSentenceId = e.nullableUuid(body, "source_sentence_id")
        val sourceMediaId = e.nullableUuid(body, "source_media_id")
        val entrySeqNode = body.get("entry_seq")
        val entrySeq = entrySeqNode?.takeUnless { it.isNull }?.let { e.long(it, "/entry_seq", 1) }
        e.throwIfAny()

        val reply = opApply.rest(call.callerTokenId(), { it.removePrefix("/set") }) { tx ->
            val views = NoteViews(tx.dsl)
            val target = views.resolve(id) ?: throw Problems.notFound("No such note.")
            if (!target["deleted_at"].isNull) throw gone("note", target["deleted_at"].asText())
            val targetId = target["id"].asText()
            if (entrySeqNode != null && target["type"].asText() != "vocab") {
                semantic("/entry_seq", "invalid_value", "entry_seq is for vocab notes, not ${target["type"].asText()}.")
            }
            val set = json.createObjectNode()
            fields?.let { set.set<JsonNode>("fields", it) }
            if (tags != null || addTags != null || removeTags != null) {
                val merged = ((tags ?: target["tags"].map { it.asText() }) + addTags.orEmpty()).toSortedSet(CodePointOrder).apply { removeAll(removeTags.orEmpty().toSet()) }
                if (merged.size > MAX_TAGS_PER_NOTE) semantic("/add_tags", "out_of_range", "A note has at most $MAX_TAGS_PER_NOTE tags.")
                set.set<JsonNode>("tags", json.createArrayNode().also { a -> merged.forEach(a::add) })
            }
            mnemonic?.let { set.put("mnemonic", it) }
            if (body.has("source_sentence") || body.has("source_sentence_id") || body.has("source_media_id")) {
                set.put("source_sentence", sourceSentence ?: "")
                set.put("source_sentence_id", sourceSentenceId)
                set.put("source_media_id", sourceMediaId)
            }
            if (entrySeqNode != null) {
                if (entrySeq == null) set.putNull("entry_seq") else set.put("entry_seq", entrySeq)
            }
            val p = json.createObjectNode().put("id", targetId)
            p.set<JsonNode>("set", set)
            tx.emit(OpType.NOTE_UPDATED, payload(p))
            // A re-match onto a held entry merges this note away; the survivor is the answer.
            val shown = views.resolve(targetId)?.get("id")?.asText() ?: targetId
            val note = views.note(shown) ?: error("note $shown vanished")
            Reply(HttpStatusCode.OK, note, tx.lastSeq, if (shown != id) "$basePath/notes/$shown" else null)
        }
        call.send(reply)
    }

    suspend fun delete(call: ApplicationCall) {
        val id = call.pathId("note_id")
        val reply = opApply.rest(call.callerTokenId()) { tx ->
            val row = NoteViews(tx.dsl).noteRow(id) ?: throw Problems.notFound("No such note.")
            // Deleting a duplicate that was merged away never deletes the survivor.
            if (!row["merged_into_id"].isNull || !row["deleted_at"].isNull) return@rest Reply(HttpStatusCode.NoContent, null)
            tx.emit(OpType.NOTE_DELETED, payload(json.createObjectNode().put("id", id)))
            Reply(HttpStatusCode.NoContent, null, tx.lastSeq)
        }
        call.send(reply)
    }

    private fun emitCard(tx: OpTx, noteId: String, kind: String) {
        tx.emit(OpType.CARD_CREATED, payload(json.createObjectNode().put("id", Uuid7.generate().toString()).put("note_id", noteId).put("kind", kind)))
    }

    private fun isLiveUserDeck(tx: OpTx, deckId: String): Boolean =
        tx.dsl.fetchExists(tx.dsl.selectOne().from("app.deck").where("id = ?::uuid and type = 'user' and deleted_at is null", deckId))

    /** `deck.item_put` at the deck's current maximum position plus 1024; key `e:<entry_seq>` or `n:<note id>`. */
    private fun putDeckItem(tx: OpTx, deckId: String, entrySeq: Long?, noteId: String) {
        val max = (tx.dsl.fetchValue("select coalesce(max(position), 0) from app.deck_item where deck_id = ?::uuid and deleted_at is null", deckId) as Number).toLong()
        val p = json.createObjectNode().put("deck_id", deckId).put("item_key", if (entrySeq != null) "e:$entrySeq" else "n:$noteId").put("position", max + DECK_POSITION_STEP)
        tx.emit(OpType.DECK_ITEM_PUT, payload(p))
    }
}

/** A 422 for a rule that needs the database. */
private fun semantic(pointer: String, code: String, detail: String): Nothing {
    Errors().apply { add(pointer, code, detail) }.throwIfAny()
    error("unreachable")
}

/** Follows `merged_into_id` to the row that answers for [id]; null when no such note. */
internal fun NoteViews.resolve(id: String): ObjectNode? {
    var row = noteRow(id) ?: return null
    var hops = 0
    while (!row["merged_into_id"].isNull && hops++ < 32) row = noteRow(row["merged_into_id"].asText()) ?: return row
    return row
}

private fun Errors.nullableUuid(o: JsonNode, key: String): String? {
    val v = o.get(key) ?: return null
    return if (v.isNull) null else uuidValue(v, ptr(key))
}

/** Field values `{key: string}`; keys must be valid field keys, values at most 100000 characters. */
internal fun Errors.fieldValues(node: JsonNode, at: String, minProps: Int): ObjectNode? {
    if (node !is ObjectNode) {
        add(at, "invalid_type", "Must be an object.")
        return null
    }
    if (node.size() < minProps) {
        add(at, "out_of_range", "At least one field.")
        return null
    }
    if (node.size() > 100) {
        add(at, "out_of_range", "At most 100 fields.")
        return null
    }
    val out = json.createObjectNode()
    for ((k, v) in node.fields().asSequence().toList()) {
        val fat = "$at/${escapePointer(k)}"
        if (!KEY_RE.matches(k) || k.length > 64) {
            add(fat, "invalid_value", "A field key is a lowercase identifier of at most 64 characters.")
            continue
        }
        stringValue(v, fat, 0, 100_000)?.let { out.put(k, it) }
    }
    return out
}

/** Characters ECMA-262 `\s` matches; a tag containing one fails the op schema's `\S+`. Not normalised: refused. */
private fun isEcmaSpace(cp: Int): Boolean =
    cp in 0x09..0x0D || cp == 0x20 || cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A ||
        cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000 || cp == 0xFEFF

/** Tags: 1 to 64 characters each, no whitespace, not `leech`; returned de-duplicated and sorted by code point. */
internal fun Errors.tagList(node: JsonNode, at: String, minItems: Int, maxItems: Int): List<String>? {
    if (!node.isArray) {
        add(at, "invalid_type", "Must be an array.")
        return null
    }
    if (node.size() < minItems || node.size() > maxItems) {
        add(at, "out_of_range", "Between $minItems and $maxItems tags.")
        return null
    }
    var ok = true
    val tags = sortedSetOf(CodePointOrder)
    node.forEachIndexed { i, t ->
        val tat = "$at/$i"
        val s = stringValue(t, tat, 1, 64)
        when {
            s == null -> ok = false
            s.codePoints().anyMatch(::isEcmaSpace) -> { add(tat, "invalid_value", "Tags must not contain whitespace."); ok = false }
            s == "leech" -> { add(tat, "invalid_value", "leech is a virtual tag and is never stored."); ok = false }
            else -> tags += s
        }
    }
    return if (ok) tags.toList() else null
}

private fun Errors.kindList(node: JsonNode): List<String>? {
    if (!node.isArray) {
        add("/card_kinds", "invalid_type", "Must be an array.")
        return null
    }
    val out = mutableListOf<String>()
    node.forEachIndexed { i, k ->
        if (!k.isTextual || !KEY_RE.matches(k.asText())) add("/card_kinds/$i", "invalid_value", "A kind is a lowercase identifier.") else out += k.asText()
    }
    if (out.size != out.toSet().size) add("/card_kinds", "invalid_value", "Kinds must be unique.")
    return out
}
