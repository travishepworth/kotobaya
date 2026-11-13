package kotobaya.ankiconnect

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.server.application.ApplicationCall
import kotobaya.auth.AnkiConnectActions
import kotobaya.auth.AnkiConnectError
import kotobaya.auth.Authenticated
import kotobaya.blobs.BlobStore
import kotobaya.cards.DefaultCardGenerator
import kotobaya.contracts.sync.OpType
import kotobaya.contracts.cards.CardGenerator
import kotobaya.notes.CodePointOrder
import kotobaya.notes.NoteViews
import kotobaya.notes.payload
import kotobaya.notes.rest
import kotobaya.notes.toNoteTypeInfo
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.Uuid7
import kotobaya.server.json
import kotobaya.sync.OpApply
import kotobaya.sync.OpTx
import org.jooq.DSLContext
import java.text.Normalizer
import java.util.UUID

private const val DEFAULT_DECK = "Kotobaya"
private const val DECK_POSITION_STEP = 1024L
private const val MAX_FIELD_CHARS = 100_000
private val KEY_RE = Regex("^[a-z][a-z0-9_]*$")
private val WHITESPACE = Regex("\\s+")

private class Attachment(val kind: String, val filename: String, val data: String, val fields: List<String>)

private class ParsedNote(
    val deckName: String,
    val modelName: String,
    val fields: Map<String, String>,
    val tags: List<String>,
    val allowDuplicate: Boolean,
    val attachments: List<Attachment>,
)

/** What the duplicate rule found for a note about to be added. */
private class Inspection(val type: ObjectNode, val entrySeq: Long?, val existing: String?, val duplicate: Boolean)

/**
 * The AnkiConnect actions (`POST /ankiconnect`, T603) behind the auth gate. `addNote` mints the same ops as
 * `POST /notes` in one [OpApply] transaction. The `POST /notes` handler is bound to an `ApplicationCall` and cannot
 * be called with parameters, so the (small) creation sequence is repeated here: `note.created`, `card.created` per
 * default kind, `deck.item_put`.
 *
 * Wiring (Main.kt): `Auth(..., ankiConnect = AnkiConnectModule(db, sync.opApply, blobStore).actions)`.
 */
class AnkiConnectModule(
    private val db: Database,
    private val opApply: OpApply,
    blobStore: BlobStore?,
    private val cards: CardGenerator = DefaultCardGenerator,
) {
    private val media = AnkiMedia(db, blobStore)

    val actions: AnkiConnectActions = AnkiConnectActions { call, auth, action, request -> dispatch(call, auth, action, request) }

    private suspend fun dispatch(call: ApplicationCall, auth: Authenticated, action: String, request: ObjectNode): Any? =
        try {
            val params = request.get("params") as? ObjectNode ?: json.createObjectNode()
            when (action) {
                "deckNames" -> deckNames()
                "modelNames" -> db.tx { NoteViews(it).noteTypes(false).map { t -> t["name"].asText() } }
                "modelFieldNames" -> modelFieldNames(params)
                "canAddNotes" -> canAddNotes(params)
                "addNote" -> addNote(auth, params)
                "findNotes" -> findNotes(params)
                "storeMediaFile" -> storeMediaFile(params)
                else -> throw AnkiConnectError("unsupported action")
            }
        } catch (e: ApiException) {
            if (e.status.value >= 500) throw e
            throw AnkiConnectError(e.detail ?: e.title)
        }

    // ------------------------------------------------------------------ read actions

    private suspend fun deckNames(): List<String> {
        val names = db.tx { dsl ->
            dsl.fetch("select distinct name from app.deck where type = 'user' and deleted_at is null").map { it.get(0, String::class.java) }
        }
        return names.sorted().ifEmpty { listOf(DEFAULT_DECK) }
    }

    private suspend fun modelFieldNames(params: ObjectNode): List<String> {
        val name = params.text("modelName") ?: throw AnkiConnectError("modelName is required")
        val type = db.tx { resolveType(it, name) } ?: throw AnkiConnectError("model was not found: $name")
        return type["fields"].map { it["key"].asText() }
    }

    private suspend fun canAddNotes(params: ObjectNode): List<Boolean> {
        val notes = params.get("notes") as? com.fasterxml.jackson.databind.node.ArrayNode ?: throw AnkiConnectError("notes is required")
        return db.tx { dsl ->
            notes.map { n ->
                val parsed = runCatching { parseNote(n) }.getOrNull() ?: return@map false
                val seen = inspect(dsl, parsed) ?: return@map false
                !seen.duplicate || parsed.allowDuplicate
            }
        }
    }

    private suspend fun findNotes(params: ObjectNode): List<Long> {
        val query = params.text("query") ?: throw AnkiConnectError("query is required")
        val terms = parseFindQuery(query) ?: return emptyList()
        val where = mutableListOf("n.deleted_at is null", "n.merged_into_id is null")
        val args = mutableListOf<Any>()
        for (t in terms) {
            when (t) {
                is FindTerm.Nid -> {
                    where += "$ANKI_ID_SQL = ?"
                    args += t.id
                }
                is FindTerm.Deck -> {
                    where += "exists (select 1 from app.deck d join app.deck_item di on di.deck_id = d.id " +
                        "where d.type = 'user' and d.deleted_at is null and d.name = ? and di.deleted_at is null " +
                        "and (di.note_id = n.id or (n.entry_seq is not null and di.entry_seq = n.entry_seq)))"
                    args += t.name
                }
                is FindTerm.Field -> {
                    where += "regexp_replace(n.fields ->> ?, '<[^>]*>', '', 'g') = ?"
                    args += t.key
                    args += stripHtml(t.value)
                }
            }
        }
        return db.tx { dsl ->
            dsl.fetch("select distinct $ANKI_ID_SQL from app.note n where ${where.joinToString(" and ")} order by 1", *args.toTypedArray())
                .map { (it.get(0) as Number).toLong() }
        }
    }

    // ------------------------------------------------------------------ media

    private suspend fun storeMediaFile(params: ObjectNode): String {
        val filename = params.text("filename")?.takeIf { it.isNotEmpty() } ?: throw AnkiConnectError("filename is required")
        // A server-side fetch of a client-chosen url is an SSRF surface (T609 reviews it); only `data` is accepted.
        if (params.get("url")?.isNull == false && params.get("data")?.isNull != false) {
            throw AnkiConnectError("url is not supported; send the file as base64 data")
        }
        val data = params.text("data") ?: throw AnkiConnectError("data is required")
        return media.storeBase64(filename, data)
    }

    // ------------------------------------------------------------------ addNote

    private suspend fun addNote(auth: Authenticated, params: ObjectNode): Long {
        val parsed = parseNote(params.get("note") ?: throw AnkiConnectError("note is required"))
        // Attachments become blobs first (the object must be durable before any row refers to it).
        val attached = parsed.attachments.map { a ->
            val bytes = try {
                java.util.Base64.getMimeDecoder().decode(a.data)
            } catch (e: IllegalArgumentException) {
                throw AnkiConnectError("attachment data is not valid base64")
            }
            a to media.store(a.filename, bytes)
        }
        val newId = Uuid7.generate()
        return opApply.rest(auth.principal.tokenId) { tx ->
            val seen = inspect(tx.dsl, parsed) ?: throw AnkiConnectError("model was not found: ${parsed.modelName}")
            val deckId = tx.dsl.fetchOptional(
                "select id::text from app.deck where name = ? and type = 'user' and deleted_at is null order by created_at, id limit 1",
                parsed.deckName,
            ).map { it.get(0, String::class.java) }.orElse(null)
            if (seen.duplicate && !parsed.allowDuplicate) throw AnkiConnectError("cannot create note because it is a duplicate")
            if (seen.duplicate && seen.entrySeq != null) {
                // The entry already has a note: never a second one; the existing note's id answers.
                val existing = seen.existing ?: error("a duplicate has a holder")
                deckId?.let { putDeckItem(tx, it, seen.entrySeq, existing) }
                return@rest ankiId(UUID.fromString(existing))
            }

            val fields = LinkedHashMap<String, String>()
            parsed.fields.forEach { (k, v) -> fields[k] = media.rewrite(v) }
            for ((a, hash) in attached) {
                val ref = if (a.kind == "audio") "[sound:$hash]" else "<img src=\"blob:$hash\">"
                for (f in a.fields) {
                    if (!KEY_RE.matches(f)) throw AnkiConnectError("invalid field name: $f")
                    fields[f] = (fields[f] ?: "") + ref
                }
            }
            val noteId = newId.toString()
            val info = seen.type.toNoteTypeInfo()
            val p = json.createObjectNode()
            p.put("id", noteId).put("note_type_id", seen.type["id"].asText())
            seen.entrySeq?.let { p.put("entry_seq", it) }
            p.set<JsonNode>("fields", json.createObjectNode().also { o -> fields.forEach { (k, v) -> o.put(k, v) } })
            p.set<JsonNode>("tags", json.createArrayNode().also { a -> parsed.tags.forEach(a::add) })
            p.put("mnemonic", "").put("source_sentence", "").put("origin", "ankiconnect")
            tx.emit(OpType.NOTE_CREATED, payload(p))
            for (kind in cards.kindsForNewNote(info, null)) {
                tx.emit(
                    OpType.CARD_CREATED,
                    payload(json.createObjectNode().put("id", Uuid7.generate().toString()).put("note_id", noteId).put("kind", kind)),
                )
            }
            deckId?.let { putDeckItem(tx, it, seen.entrySeq, noteId) }
            ankiId(newId)
        }
    }

    private fun putDeckItem(tx: OpTx, deckId: String, entrySeq: Long?, noteId: String) {
        val max = (tx.dsl.fetchValue("select coalesce(max(position), 0) from app.deck_item where deck_id = ?::uuid and deleted_at is null", deckId) as Number).toLong()
        val p = json.createObjectNode().put("deck_id", deckId).put("item_key", if (entrySeq != null) "e:$entrySeq" else "n:$noteId")
            .put("position", max + DECK_POSITION_STEP)
        tx.emit(OpType.DECK_ITEM_PUT, payload(p))
    }

    // ------------------------------------------------------------------ model, matcher, duplicate rule

    private fun resolveType(dsl: DSLContext, name: String): ObjectNode? = NoteViews(dsl).noteTypes(false).firstOrNull { it["name"].asText() == name }

    /** Resolves the model, matches the entry (vocab only) and applies the duplicate rule. Null: unknown model. */
    private fun inspect(dsl: DSLContext, note: ParsedNote): Inspection? {
        val type = resolveType(dsl, note.modelName) ?: return null
        val entry = if (type["base_type"].asText() == "vocab") matchEntry(dsl, note.fields["expression"], note.fields["reading"]) else null
        if (entry != null) {
            val holder = dsl.fetchOptional(
                "select id::text from app.note where entry_seq = ? and deleted_at is null and merged_into_id is null order by id limit 1", entry,
            ).map { it.get(0, String::class.java) }.orElse(null)
            return Inspection(type, entry, holder, holder != null)
        }
        val first = type["fields"].firstOrNull()?.get("key")?.asText()
        val value = first?.let { note.fields[it] }?.let(::stripHtml)?.trim().orEmpty()
        val twin = if (first == null || value.isEmpty()) {
            null
        } else {
            dsl.fetchOptional(
                "select id::text from app.note where note_type_id = ?::uuid and deleted_at is null and merged_into_id is null " +
                    "and regexp_replace(fields ->> ?, '<[^>]*>', '', 'g') = ? order by id limit 1",
                type["id"].asText(), first, value,
            ).map { it.get(0, String::class.java) }.orElse(null)
        }
        return Inspection(type, null, twin, twin != null)
    }

    /**
     * The dictionary entry for `expression` (any form) whose readings include `reading` when one is given.
     * Prefers an exact-form hit, then common entries, then frequency, then the lowest seq. Retired entries never match.
     * (The matcher the contract calls "the core's" does not exist in core yet; this is the server-side equivalent.)
     */
    private fun matchEntry(dsl: DSLContext, expression: String?, reading: String?): Long? {
        val expr = expression?.let(::stripHtml)?.trim().orEmpty()
        if (expr.isEmpty()) return null
        val read = reading?.let(::stripHtml)?.trim().orEmpty()
        val sql = StringBuilder(
            "select e.seq from ref.dict_entry e where e.retired_release_id is null " +
                "and exists (select 1 from ref.dict_entry_form f where f.seq = e.seq and f.norm = ?)",
        )
        val args = mutableListOf<Any>(norm(expr))
        if (read.isNotEmpty()) {
            sql.append(" and exists (select 1 from ref.dict_entry_form r where r.seq = e.seq and r.kind = 'reading' and r.norm = ?)")
            args += norm(read)
        }
        sql.append(
            " order by (exists (select 1 from ref.dict_entry_form x where x.seq = e.seq and x.form = ?)) desc, " +
                "e.is_common desc, e.freq_rank asc nulls last, e.seq asc limit 1",
        )
        args += expr
        return dsl.fetchOptional(sql.toString(), *args.toTypedArray()).map { (it.get(0) as Number).toLong() }.orElse(null)
    }

    /** `ref.dict_entry_form.norm`: NFKC, then katakana folded to hiragana. */
    private fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC)
        return buildString { n.forEach { c -> append(if (c in 'ァ'..'ヶ') c - 0x60 else c) } }
    }

    // ------------------------------------------------------------------ parsing

    private fun ObjectNode.text(key: String): String? = get(key)?.takeIf { it.isTextual }?.asText()

    private fun parseNote(node: JsonNode): ParsedNote {
        if (node !is ObjectNode) throw AnkiConnectError("note must be an object")
        val modelName = node.text("modelName")?.takeIf { it.isNotEmpty() } ?: throw AnkiConnectError("modelName is required")
        val deckName = node.text("deckName") ?: ""
        val rawFields = node.get("fields") as? ObjectNode ?: throw AnkiConnectError("fields is required")
        val fields = LinkedHashMap<String, String>()
        for ((k, v) in rawFields.fields().asSequence().toList()) {
            if (!KEY_RE.matches(k) || k.length > 64) throw AnkiConnectError("invalid field name: $k")
            val s = if (v.isTextual) v.asText() else throw AnkiConnectError("field $k must be a string")
            if (s.length > MAX_FIELD_CHARS) throw AnkiConnectError("field $k is too long")
            fields[k] = s
        }
        val tags = sortedSetOf(CodePointOrder)
        (node.get("tags") as? com.fasterxml.jackson.databind.node.ArrayNode)?.forEach { t ->
            if (!t.isTextual) throw AnkiConnectError("tags must be strings")
            val tag = t.asText().trim().replace(WHITESPACE, "_").take(64)
            if (tag.isNotEmpty() && tag != "leech") tags += tag
        }
        val allow = node.get("options")?.get("allowDuplicate")?.asBoolean(false) ?: false
        val attachments = listOf("audio", "picture").flatMap { kind ->
            val n = node.get(kind)
            val items = when {
                n == null || n.isNull -> emptyList()
                n.isArray -> n.toList()
                else -> listOf(n)
            }
            items.map { a ->
                if (a.get("url")?.isNull == false && a.get("data")?.isNull != false) {
                    throw AnkiConnectError("url is not supported; send the file as base64 data")
                }
                Attachment(
                    kind,
                    a.get("filename")?.takeIf { it.isTextual }?.asText() ?: throw AnkiConnectError("$kind filename is required"),
                    a.get("data")?.takeIf { it.isTextual }?.asText() ?: throw AnkiConnectError("$kind data is required"),
                    a.get("fields")?.map { it.asText() }.orEmpty(),
                )
            }
        }
        return ParsedNote(deckName, modelName, fields, tags.toList(), allow, attachments)
    }
}
