package kotobaya.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.contracts.sync.OpType.*
import kotobaya.server.json
import org.jooq.DSLContext
import java.util.UUID

/**
 * The clock rule (SYNC.md 7.1) on one loaded row. [register] writes iff the register has no clock
 * or the new clock is greater, stamps the clock and raises `updated_at` to `wall(h)`; it records
 * which columns changed so [Rows.update] writes only those.
 */
internal class RowEditor(val row: ObjectNode) {
    val dirty = linkedSetOf<String>()
    private val clocks: ObjectNode
        get() = (row["clocks"] as? ObjectNode) ?: SyncJson.obj().also { row.set<JsonNode>("clocks", it) }

    fun clockOf(name: String): String? = clocks[name]?.asText()

    fun clockNames(): List<String> = clocks.fieldNames().asSequence().toList()

    fun set(col: String, v: JsonNode) {
        row.set<JsonNode>(col, v)
        dirty += col
    }

    fun setKey(col: String, key: String, v: JsonNode?) {
        val o = (row[col] as? ObjectNode) ?: SyncJson.obj().also { row.set<JsonNode>(col, it) }
        if (v == null || v.isNull) o.remove(key) else o.set<JsonNode>(key, v)
        dirty += col
    }

    fun register(name: String, h: String, write: (RowEditor) -> Unit): Boolean {
        val cur = clockOf(name)
        if (cur != null && h <= cur) return false
        clocks.put(name, h)
        dirty += "clocks"
        write(this)
        bumpUpdated(wall(h))
        return true
    }

    fun bumpUpdated(ms: Long) {
        val cur = SyncJson.ms(row["updated_at"]) ?: Long.MIN_VALUE
        if (ms > cur) set("updated_at", SyncJson.nodes.textNode(SyncJson.iso(ms)))
    }
}

/** A register write built from a payload or copied from a merge loser: the register name and how to write its value. */
internal class RegWrite(val name: String, val write: (RowEditor) -> Unit)

internal fun wall(h: String): Long = DefaultHlcRules.wall(h)

/** What an apply asks the transaction to do after the op's `change` row is written (server-emitted consequences). */
internal class FollowUp(val type: OpType, val payload: JsonObject)

/**
 * Applies one op to the server's tables (SYNC.md 7–9, OPS.md "Apply"), identically for pushed and
 * server-minted ops. Callers have validated the op (5.3) — the referenced rows exist — and hold the
 * op-apply lock. Returns follow-up ops the server must emit as consequences (`media.deleted` →
 * the media deck's `deck.deleted`).
 *
 * Idempotent (7.8): applying an op a second time changes nothing (creation of an existing id,
 * equal clocks, an existing review id, a not-smaller undo, an already merged loser, a version that
 * is not higher). The one exception is `media.content_published` with an equal version (7.6).
 */
internal class OpApplier(
    private val dsl: DSLContext,
    private val cache: CardCacheHook,
    private val autoStatus: AutoStatusHook,
    private val onCollision: (Op, Collision) -> Long,
) {
    private val rows = Rows(dsl)
    private val n = SyncJson.nodes

    fun apply(op: Op): List<FollowUp> = applyOp(op).also { touchAfter(op) }

    /**
     * Entries whose derived `auto_status` an op may have moved through a card cache (OPS.md: every replica
     * re-derives it; the device's `AutoStatus.entriesOf`). Note/card creation, deletion and merge touch their own
     * entries inside [applyOp]; here are the ops that change a card's state or interval without a note/card row write.
     */
    private fun touchAfter(op: Op) {
        val p = op.payload
        when (op.opType) {
            REVIEW_LOGGED -> touchEntriesOfCards(listOfNotNull(p.str("card_id")))
            REVIEW_UNDONE -> dsl.fetchOptional("select card_id::text from app.review_log where id = ?::uuid", p.reqStr("review_id"))
                .ifPresent { touchEntriesOfCards(listOf(it.get(0, String::class.java))) }
            CARD_PRESET_SET -> touchEntriesOfCards(listOf(p.reqStr("id")))
            NOTE_MERGED -> touchEntriesOfNotes(listOf(p.reqStr("loser_id"), p.reqStr("survivor_id")))
            else -> if (op.opType.wire.startsWith("fsrs_params.")) {
                val all = dsl.fetch("select entry_seq from app.note where entry_seq is not null union select entry_seq from app.word_status")
                    .map { it.get(0, Number::class.java).toLong() }.toSet()
                if (all.isNotEmpty()) autoStatus.entriesTouched(dsl, all)
            }
        }
    }

    private fun applyOp(op: Op): List<FollowUp> {
        val p = op.payload
        val h = op.hlc
        return when (op.opType) {
            NOTE_TYPE_CREATED -> noteTypeCreated(p, h)
            NOTE_TYPE_UPDATED -> registers("note_type", p.reqStr("id"), h, setWrites(p.reqObj("set"), jsonKeyed = setOf("templates")))
            NOTE_TYPE_DELETED -> deleted("note_type", p.reqStr("id"), h).let { none() }
            NOTE_CREATED -> noteCreated(op)
            NOTE_UPDATED -> noteUpdated(op)
            NOTE_DELETED -> {
                deleted("note", p.reqStr("id"), h)
                touchEntriesOfNotes(listOf(p.reqStr("id")))
                none()
            }
            NOTE_MERGED -> merged("note", p.reqStr("loser_id"), p.reqStr("survivor_id"), h).let { none() }
            CARD_CREATED -> cardCreated(op)
            CARD_SUSPENDED -> cardRegister(p.reqStr("id"), h, RegWrite("suspended") { it.set("suspended", n.booleanNode(true)) })
            CARD_UNSUSPENDED -> cardRegister(p.reqStr("id"), h, RegWrite("suspended") { it.set("suspended", n.booleanNode(false)) })
            CARD_BURIED -> cardRegister(p.reqStr("id"), h, RegWrite("buried_until") { it.set("buried_until", instant(p.long("until"))) })
            CARD_UNBURIED -> cardRegister(p.reqStr("id"), h, RegWrite("buried_until") { it.set("buried_until", n.nullNode()) })
            CARD_FLAG_SET -> cardRegister(p.reqStr("id"), h, RegWrite("flags") { it.set("flags", p.node("flags")) })
            CARD_PRESET_SET -> {
                val won = cardRegisterWon(p.reqStr("id"), h, RegWrite("preset_id") { it.set("preset_id", p.node("preset_id")) })
                if (won.isNotEmpty()) cache.replayCards(dsl, won)
                none()
            }
            CARD_DELETED -> {
                deleted("card", p.reqStr("id"), h)
                touchEntriesOfCards(listOf(p.reqStr("id")))
                none()
            }
            CARD_MERGED -> {
                val survivor = merged("card", p.reqStr("loser_id"), p.reqStr("survivor_id"), h)
                if (survivor != null) {
                    cache.replayCards(dsl, setOf(UUID.fromString(survivor)))
                    touchEntriesOfCards(listOf(survivor))
                }
                none()
            }
            REVIEW_LOGGED -> reviewLogged(op)
            REVIEW_UNDONE -> reviewUndone(op)
            WORD_STATUS_SET -> wordStatus(p.reqLong("entry_seq"), p.reqStr("status"), p.str("origin") ?: "manual", h)
            WORD_STATUS_CLEARED -> wordStatus(p.reqLong("entry_seq"), null, null, h)
            DECK_CREATED -> deckCreated(p, h)
            DECK_UPDATED -> registers("deck", p.reqStr("id"), h, setWrites(p.reqObj("set"), jsonKeyed = setOf("settings")))
            DECK_DELETED -> deleted("deck", p.reqStr("id"), h).let { none() }
            DECK_CONTENT_PUBLISHED -> published("deck", p.reqStr("deck_id"), p, h, emptyList(), allowEqual = false)
            DECK_ITEM_PUT -> deckItem(p, h, removed = false)
            DECK_ITEM_REMOVED -> deckItem(p, h, removed = true)
            CONFUSABLE_SUGGESTED -> confusable(p, h, "suggested")
            CONFUSABLE_CONFIRMED -> confusable(p, h, "confirmed")
            CONFUSABLE_REJECTED -> confusable(p, h, "rejected")
            GRAMMAR_CREATED -> grammarCreated(p, h)
            GRAMMAR_UPDATED -> registers("grammar_point", p.reqStr("id"), h, setWrites(p.reqObj("set")))
            GRAMMAR_DELETED -> deleted("grammar_point", p.reqStr("id"), h).let { none() }
            GRAMMAR_SPAN_PUT -> span(p, h, removed = false)
            GRAMMAR_SPAN_REMOVED -> span(p, h, removed = true)
            SETTINGS_SET -> settings(p, h)
            FSRS_PARAMS_CREATED -> presetCreated(p, h)
            FSRS_PARAMS_UPDATED -> {
                val id = p.reqStr("id")
                val ed = editor("fsrs_params", id)
                val won = setWrites(p.reqObj("set")).filter { w -> ed.register(w.name, h, w.write) }.map { it.name }
                rows.update("fsrs_params", ID, ed.row, ed.dirty)
                if (won.any { it != "name" }) cache.presetChanged(dsl, UUID.fromString(id), PresetChange.UPDATED)
                none()
            }
            FSRS_PARAMS_DELETED -> {
                if (deleted("fsrs_params", p.reqStr("id"), h)) cache.presetChanged(dsl, UUID.fromString(p.reqStr("id")), PresetChange.DELETED)
                none()
            }
            FSRS_PARAMS_PUBLISHED -> presetPublished(p, h)
            MEDIA_CREATED -> mediaCreated(p, h)
            MEDIA_UPDATED -> registers("media", p.reqStr("id"), h, setWrites(p.reqObj("set")))
            MEDIA_DELETED -> mediaDeleted(p.reqStr("id"), h)
            MEDIA_CONTENT_PUBLISHED ->
                published("media", p.reqStr("media_id"), p, h, listOf("parse_status", "parse_error"), allowEqual = true)
            PARSE_OVERRIDE_PUT -> parseOverridePut(p, h)
            PARSE_OVERRIDE_DELETED -> parseOverrideDeleted(p.reqStr("id"), h)
        }
    }

    // ------------------------------------------------------------------ generic pieces

    private fun none(): List<FollowUp> = emptyList()

    private fun instant(ms: Long?): JsonNode = if (ms == null) n.nullNode() else n.textNode(SyncJson.iso(ms))

    private fun editor(table: String, id: String): RowEditor =
        RowEditor(rows.load(table, Rows.key("id" to id)) ?: error("$table $id vanished after validation"))

    private fun base(id: String, h: String): ObjectNode = SyncJson.obj().apply {
        put("id", id)
        put("created_at", SyncJson.iso(wall(h)))
        put("updated_at", SyncJson.iso(wall(h)))
        set<JsonNode>("clocks", SyncJson.obj())
    }

    private fun stamp(row: ObjectNode, reg: String, h: String) {
        (row["clocks"] as ObjectNode).put(reg, h)
    }

    private fun exists(table: String, id: String) = rows.exists(table, "id", UUID.fromString(id))

    /**
     * Register writes for a `*.updated` `set`: one per member; members named in [jsonKeyed] are JSON
     * object columns with one register per key (`settings.<key>`; null removes the key, the clock stays).
     * Note's `source_sentence` covers its three columns (OPS.md).
     */
    private fun setWrites(set: JsonObject, jsonKeyed: Set<String> = emptySet()): List<RegWrite> = buildList {
        for ((name, value) in set.members) {
            when {
                name in jsonKeyed -> (value as JsonObject).members.forEach { (k, v) ->
                    add(RegWrite("$name.$k") { it.setKey(name, k, SyncJson.toJackson(v)) })
                }
                name == "source_sentence_id" || name == "source_media_id" -> Unit // part of source_sentence
                name == "source_sentence" -> add(
                    RegWrite("source_sentence") {
                        it.set("source_sentence", set.node("source_sentence"))
                        it.set("source_sentence_id", set.node("source_sentence_id"))
                        it.set("source_media_id", set.node("source_media_id"))
                    },
                )
                else -> add(RegWrite(name) { it.set(name, SyncJson.toJackson(value)) })
            }
        }
    }

    /** Applies [writes] by the clock rule to one row; returns the names that won. */
    private fun registers(table: String, id: String, h: String, writes: List<RegWrite>): List<FollowUp> {
        val ed = editor(table, id)
        writes.forEach { ed.register(it.name, h, it.write) }
        rows.update(table, ID, ed.row, ed.dirty)
        return none()
    }

    /** Register `deleted` (7.3). Returns true on the first transition to tombstoned. */
    private fun deleted(table: String, id: String, h: String): Boolean {
        val ed = editor(table, id)
        val wasLive = ed.row["deleted_at"].let { it == null || it.isNull }
        ed.register("deleted", h) { it.set("deleted_at", instant(wall(h))) }
        rows.update(table, ID, ed.row, ed.dirty)
        return wasLive && !ed.row["deleted_at"].isNull
    }

    /** Value of register [reg] in [row], as a write (merge forwarding, 8.2 step 1). */
    private fun copyOf(row: ObjectNode, reg: String): RegWrite = when {
        "." in reg -> {
            val col = reg.substringBefore('.')
            val key = reg.substringAfter('.')
            RegWrite(reg) { it.setKey(col, key, row[col]?.get(key)?.deepCopy()) }
        }
        reg == "source_sentence" -> RegWrite(reg) { e ->
            listOf("source_sentence", "source_sentence_id", "source_media_id").forEach { e.set(it, row[it].deepCopy()) }
        }
        else -> RegWrite(reg) { it.set(reg, row[reg].deepCopy()) }
    }

    /**
     * `note.merged` / `card.merged` (SYNC.md 8.2). Returns the survivor actually used, or null when
     * the op was a no-op (loser already merged).
     */
    private fun merged(table: String, loserId: String, survivorId: String, h: String): String? {
        val loser = editor(table, loserId)
        if (!loser.row["merged_into_id"].isNull) return null
        var survivor = editor(table, survivorId)
        val target = survivor.row["merged_into_id"]
        if (!target.isNull) survivor = editor(table, target.asText())
        val wId = survivor.row["id"].asText()
        if (wId == loserId) return null
        for (reg in loser.clockNames()) {
            if (reg in notForwarded(table)) continue
            val w = copyOf(loser.row, reg)
            survivor.register(reg, checkNotNull(loser.clockOf(reg)) { "clock of $reg" }, w.write)
        }
        rows.update(table, ID, survivor.row, survivor.dirty)
        if (loser.row["deleted_at"].isNull) loser.set("deleted_at", instant(wall(h)))
        loser.set("merged_into_id", n.textNode(wId))
        loser.bumpUpdated(wall(h))
        rows.update(table, ID, loser.row, loser.dirty)
        dsl.execute("update app.$table set merged_into_id = ?::uuid where merged_into_id = ?::uuid", wId, loserId)
        return wId
    }

    private fun notForwarded(table: String): Set<String> = if (table == "note") setOf("deleted", "entry_seq") else setOf("deleted")

    /** A register op on a note or card: applied to the addressed row and, if it is a merge loser, to its survivor (8.3). Returns the ids it won on. */
    private fun forwarded(table: String, id: String, h: String, writes: List<RegWrite>, beforeWrite: (RowEditor, List<String>) -> Unit = { _, _ -> }): Set<String> {
        val ed = editor(table, id)
        val won = writes.filter { ed.register(it.name, h, it.write) }.map { it.name }
        beforeWrite(ed, won)
        rows.update(table, ID, ed.row, ed.dirty)
        val out = if (won.isNotEmpty()) mutableSetOf(id) else mutableSetOf()
        val target = ed.row["merged_into_id"]
        if (!target.isNull) {
            val sw = writes.filter { it.name !in notForwarded(table) }
            if (sw.isNotEmpty()) {
                val s = editor(table, target.asText())
                if (sw.count { s.register(it.name, h, it.write) } > 0) out += target.asText()
                rows.update(table, ID, s.row, s.dirty)
            }
        }
        return out
    }

    private fun cardRegister(id: String, h: String, w: RegWrite): List<FollowUp> {
        forwarded("card", id, h, listOf(w))
        return none()
    }

    /** Cards (as maintained caches: never a merge loser) on which a card register write won. */
    private fun cardRegisterWon(id: String, h: String, w: RegWrite): Set<UUID> =
        forwarded("card", id, h, listOf(w)).filter { cid ->
            dsl.fetchValue("select merged_into_id is null from app.card where id = ?::uuid", cid) as Boolean
        }.map(UUID::fromString).toSet()

    private fun touchEntriesOfNotes(noteIds: Collection<String>) {
        if (noteIds.isEmpty()) return
        val seqs = noteIds.mapNotNull { (dsl.fetchValue("select entry_seq from app.note where id = ?::uuid", it) as Number?)?.toLong() }.toSet()
        if (seqs.isNotEmpty()) autoStatus.entriesTouched(dsl, seqs)
    }

    private fun touchEntriesOfCards(cardIds: Collection<String>) {
        val notes = cardIds.mapNotNull { dsl.fetchValue("select note_id::text from app.card where id = ?::uuid", it) as String? }
        touchEntriesOfNotes(notes)
    }

    // ------------------------------------------------------------------ note_type

    private fun noteTypeCreated(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        if (exists("note_type", id)) return none()
        val row = base(id, h)
        row.put("name", p.reqStr("name")); stamp(row, "name", h)
        row.put("base_type", p.reqStr("base_type"))
        row.put("builtin", false)
        row.set<JsonNode>("fields", p.node("fields")); stamp(row, "fields", h)
        val templates = SyncJson.obj()
        p.reqObj("templates").members.forEach { (k, v) ->
            if (!isEmpty(v)) {
                templates.set<JsonNode>(k, SyncJson.toJackson(v)); stamp(row, "templates.$k", h)
            }
        }
        row.set<JsonNode>("templates", templates)
        val css = p.str("css") ?: ""
        row.put("css", css)
        if (css.isNotEmpty()) stamp(row, "css", h)
        rows.insert("note_type", row)
        return none()
    }

    // ------------------------------------------------------------------ note

    private fun liveNoteHolding(col: String, value: JsonNode, except: String): String? =
        dsl.fetchOptional(
            "select id::text from app.note where $col = (select k.$col from jsonb_populate_record(null::app.note, ?::jsonb) k) " +
                "and deleted_at is null and id <> ?::uuid",
            json.writeValueAsString(SyncJson.obj().set<JsonNode>(col, value)), except,
        ).map { it.get(0, String::class.java) }.orElse(null)

    private fun noteCreated(op: Op): List<FollowUp> {
        val p = op.payload
        val h = op.hlc
        val id = p.reqStr("id")
        if (exists("note", id)) return none()
        val baseType = dsl.fetchValue("select base_type from app.note_type where id = ?::uuid", p.reqStr("note_type_id")) as String
        val row = base(id, h)
        row.put("note_type_id", p.reqStr("note_type_id"))
        row.put("type", baseType)
        row.set<JsonNode>("entry_seq", p.node("entry_seq"))
        if (p.member("entry_seq") != null) stamp(row, "entry_seq", h)
        row.set<JsonNode>("kanji_literal", p.node("kanji_literal"))
        row.set<JsonNode>("grammar_point_id", p.node("grammar_point_id"))
        val fields = SyncJson.obj()
        p.obj("fields")?.members?.forEach { (k, v) ->
            if (!isEmpty(v)) {
                fields.set<JsonNode>(k, SyncJson.toJackson(v)); stamp(row, "fields.$k", h)
            }
        }
        row.set<JsonNode>("fields", fields)
        val tags = p.arr("tags") ?: JsonArray(emptyList())
        row.set<JsonNode>("tags", SyncJson.toJackson(tags))
        if (tags.items.isNotEmpty()) stamp(row, "tags", h)
        val mnemonic = p.str("mnemonic") ?: ""
        row.put("mnemonic", mnemonic)
        if (mnemonic.isNotEmpty()) stamp(row, "mnemonic", h)
        val src = p.str("source_sentence") ?: ""
        row.put("source_sentence", src)
        row.set<JsonNode>("source_sentence_id", p.node("source_sentence_id"))
        row.set<JsonNode>("source_media_id", p.node("source_media_id"))
        if (src.isNotEmpty() || p.member("source_sentence_id") != null || p.member("source_media_id") != null) stamp(row, "source_sentence", h)
        row.put("origin", p.str("origin") ?: "manual")
        row.put("created_by_device_id", op.deviceId)
        // 8.1: the newcomer collides with a live holder of the same reference → tombstoned in this very insert.
        for (col in listOf("entry_seq", "kanji_literal", "grammar_point_id")) {
            val v = row[col]
            if (v.isNull) continue
            val holder = liveNoteHolding(col, v, id) ?: continue
            val at = onCollision(op, Collision("note", UUID.fromString(id), UUID.fromString(holder), "$col=${v.asText()}"))
            row.put("deleted_at", SyncJson.iso(at))
            break
        }
        rows.insert("note", row)
        p.long("entry_seq")?.let { autoStatus.entriesTouched(dsl, setOf(it)) }
        return none()
    }

    private fun noteUpdated(op: Op): List<FollowUp> {
        val p = op.payload
        val id = p.reqStr("id")
        val set = p.reqObj("set")
        val before = (dsl.fetchValue("select entry_seq from app.note where id = ?::uuid", id) as Number?)?.toLong()
        forwarded("note", id, op.hlc, setWrites(set, jsonKeyed = setOf("fields"))) { ed, won ->
            val seq = ed.row["entry_seq"]
            if ("entry_seq" in won && !seq.isNull && ed.row["deleted_at"].isNull) {
                liveNoteHolding("entry_seq", seq, id)?.let { holder ->
                    val at = onCollision(op, Collision("note", UUID.fromString(id), UUID.fromString(holder), "entry_seq=${seq.asText()}"))
                    ed.set("deleted_at", instant(at))
                }
            }
        }
        if (set.has("entry_seq")) {
            val after = (dsl.fetchValue("select entry_seq from app.note where id = ?::uuid", id) as Number?)?.toLong()
            val touched = setOfNotNull(before, after)
            if (before != after && touched.isNotEmpty()) autoStatus.entriesTouched(dsl, touched)
        }
        return none()
    }

    // ------------------------------------------------------------------ card

    private fun cardCreated(op: Op): List<FollowUp> {
        val p = op.payload
        val h = op.hlc
        val id = p.reqStr("id")
        if (exists("card", id)) return none()
        val row = base(id, h)
        row.put("note_id", p.reqStr("note_id"))
        row.put("kind", p.reqStr("kind"))
        val preset = p.str("preset_id") ?: Reserved.DEFAULT_PRESET_ID
        row.put("preset_id", preset)
        if (preset != Reserved.DEFAULT_PRESET_ID) stamp(row, "preset_id", h)
        val suspended = p.bool("suspended") ?: false
        row.put("suspended", suspended)
        if (suspended) stamp(row, "suspended", h)
        row.set<JsonNode>("buried_until", instant(p.long("buried_until")))
        if (p.member("buried_until") != null) stamp(row, "buried_until", h)
        val flags = p.long("flags") ?: 0
        row.put("flags", flags)
        if (flags != 0L) stamp(row, "flags", h)
        val holder = dsl.fetchOptional(
            "select id::text from app.card where note_id = ?::uuid and kind = ? and deleted_at is null and id <> ?::uuid",
            p.reqStr("note_id"), p.reqStr("kind"), id,
        ).map { it.get(0, String::class.java) }.orElse(null)
        if (holder != null) {
            val at = onCollision(op, Collision("card", UUID.fromString(id), UUID.fromString(holder), "kind=${p.reqStr("kind")}"))
            row.put("deleted_at", SyncJson.iso(at))
        }
        rows.insert("card", row)
        touchEntriesOfNotes(listOf(p.reqStr("note_id")))
        return none()
    }

    // ------------------------------------------------------------------ review_log

    private fun targetCard(cardId: String): UUID {
        val merged = dsl.fetchValue("select merged_into_id::text from app.card where id = ?::uuid", cardId) as String?
        return UUID.fromString(merged ?: cardId)
    }

    private fun reviewLogged(op: Op): List<FollowUp> {
        val p = op.payload
        val id = p.reqStr("id")
        if (exists("review_log", id)) return none() // first write wins (7.9 rule 1)
        val row = SyncJson.obj()
        row.put("id", id)
        row.put("card_id", p.reqStr("card_id"))
        row.put("device_id", op.deviceId)
        row.put("hlc", p.str("replay_hlc") ?: op.hlc)
        row.set<JsonNode>("reviewed_at", instant(p.long("reviewed_at")))
        for (c in listOf("utc_offset_min", "review_type", "rating", "button", "duration_ms", "manual_action", "sentence_id", "import_source")) {
            row.set<JsonNode>(c, p.node(c))
        }
        row.set<JsonNode>("manual_due_at", instant(p.long("manual_due_at")))
        rows.insert("review_log", row)
        if (p.str("review_type") in SCHEDULE_FEEDING) cache.reviewLogged(dsl, UUID.fromString(id), targetCard(p.reqStr("card_id")))
        return none()
    }

    private fun reviewUndone(op: Op): List<FollowUp> {
        val id = op.payload.reqStr("review_id")
        val r = dsl.fetchOne("select undone_hlc::text, card_id::text, review_type from app.review_log where id = ?::uuid", id)
            ?: error("review $id vanished after validation")
        val wasUndone = r.get(0) != null
        val changed = dsl.execute(
            AppendOnlyGuard.UNDO_SQL,
            op.hlc, SyncJson.iso(wall(op.hlc)), UUID.fromString(id), op.hlc,
        )
        if (changed == 1 && !wasUndone && r.get(2, String::class.java) in SCHEDULE_FEEDING) {
            cache.reviewUndone(dsl, UUID.fromString(id), targetCard(r.get(1, String::class.java)))
        }
        return none()
    }

    // ------------------------------------------------------------------ word_status

    private fun wordStatus(seq: Long, status: String?, origin: String?, h: String): List<FollowUp> {
        val key = Rows.key("entry_seq" to seq)
        val existing = rows.load("word_status", key)
        val row = existing ?: SyncJson.obj().apply {
            put("entry_seq", seq)
            put("status", "new")
            putNull("auto_status")
            putNull("override_status")
            putNull("override_hlc")
            putNull("override_origin")
            put("updated_at", SyncJson.iso(wall(h)))
        }
        val cur = row["override_hlc"]
        if (!cur.isNull && h <= cur.asText()) return none()
        val ed = RowEditor(row)
        ed.set("override_status", if (status == null) n.nullNode() else n.textNode(status))
        ed.set("override_origin", if (origin == null) n.nullNode() else n.textNode(origin))
        ed.set("override_hlc", n.textNode(h))
        val auto = row["auto_status"].takeUnless { it.isNull }?.asText()
        ed.set("status", n.textNode(status ?: auto ?: "new"))
        ed.bumpUpdated(wall(h))
        if (existing == null) rows.insert("word_status", ed.row) else rows.update("word_status", listOf("entry_seq"), ed.row, ed.dirty)
        return none()
    }

    // ------------------------------------------------------------------ deck

    private fun deckCreated(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        if (exists("deck", id)) return none()
        val row = base(id, h)
        row.put("name", p.reqStr("name")); stamp(row, "name", h)
        row.put("type", p.reqStr("type"))
        val priority = p.long("priority") ?: 0
        row.put("priority", priority)
        if (priority != 0L) stamp(row, "priority", h)
        val enabled = p.bool("enabled") ?: true
        row.put("enabled", enabled)
        if (!enabled) stamp(row, "enabled", h)
        for (c in listOf("new_cards_per_day", "reviews_per_day", "query")) {
            row.set<JsonNode>(c, p.node(c))
            if (p.member(c) != null) stamp(row, c, h)
        }
        val settings = SyncJson.obj()
        p.obj("settings")?.members?.forEach { (k, v) ->
            if (!isEmpty(v)) {
                settings.set<JsonNode>(k, SyncJson.toJackson(v)); stamp(row, "settings.$k", h)
            }
        }
        row.set<JsonNode>("settings", settings)
        row.set<JsonNode>("media_id", p.node("media_id"))
        row.set<JsonNode>("list_key", p.node("list_key"))
        rows.insert("deck", row)
        return none()
    }

    /** `deck.content_published` (>) / `media.content_published` (>=, 7.6). */
    private fun published(table: String, id: String, p: JsonObject, h: String, extra: List<String>, allowEqual: Boolean): List<FollowUp> {
        val ed = editor(table, id)
        val stored = ed.row["content_version"].asLong()
        val v = p.reqLong("content_version")
        if (v > stored || (allowEqual && v == stored)) {
            (listOf("content_version", "content_blob_hash") + extra).forEach { ed.set(it, p.node(it)) }
            ed.bumpUpdated(wall(h))
            rows.update(table, ID, ed.row, ed.dirty)
        }
        return none()
    }

    /** Whole-row LWW on `hlc` (7.4): replace every op-written column iff no row or a greater clock. */
    private fun wholeRow(table: String, keyCols: List<String>, row: ObjectNode, h: String) {
        val key = SyncJson.obj().also { k -> keyCols.forEach { k.set<JsonNode>(it, row[it]) } }
        val existing = rows.load(table, key)
        when {
            existing == null -> rows.insert(table, row)
            existing["hlc"].isNull || h > existing["hlc"].asText() ->
                rows.update(table, keyCols, row, row.fieldNames().asSequence().filter { it !in keyCols }.toList())
        }
    }

    private fun deckItem(p: JsonObject, h: String, removed: Boolean): List<FollowUp> {
        val key = p.reqStr("item_key")
        val row = SyncJson.obj()
        row.put("deck_id", p.reqStr("deck_id"))
        row.put("item_key", key)
        if (key.startsWith("e:")) {
            row.put("entry_seq", key.substring(2).toLong()); row.putNull("note_id")
        } else {
            row.putNull("entry_seq"); row.put("note_id", key.substring(2))
        }
        row.put("position", if (removed) 0 else p.reqLong("position"))
        row.put("occurrence_count", 0)
        row.put("added_at", SyncJson.iso(wall(h)))
        row.set<JsonNode>("deleted_at", if (removed) instant(wall(h)) else n.nullNode())
        row.put("hlc", h)
        wholeRow("deck_item", listOf("deck_id", "item_key"), row, h)
        return none()
    }

    // ------------------------------------------------------------------ confusable

    private fun confusable(p: JsonObject, h: String, status: String): List<FollowUp> {
        val keyCols = listOf("note_a_id", "note_b_id", "reason")
        val key = SyncJson.obj().also { k -> keyCols.forEach { c -> k.put(c, p.reqStr(c)) } }
        val existing = rows.load("confusable", key)
        val w = wall(h)
        if (existing == null) {
            key.put("status", status); key.put("hlc", h)
            key.put("created_at", SyncJson.iso(w)); key.put("updated_at", SyncJson.iso(w))
            rows.insert("confusable", key)
            return none()
        }
        val ed = RowEditor(existing)
        if (w < SyncJson.reqMs(existing["created_at"])) ed.set("created_at", n.textNode(SyncJson.iso(w)))
        fun rank(s: String) = if (s == "suggested") 0 else 1
        val r = rank(status)
        val sr = rank(existing["status"].asText())
        if (r > sr || (r == sr && h > existing["hlc"].asText())) {
            ed.set("status", n.textNode(status)); ed.set("hlc", n.textNode(h)); ed.set("updated_at", n.textNode(SyncJson.iso(w)))
        }
        rows.update("confusable", keyCols, ed.row, ed.dirty)
        return none()
    }

    // ------------------------------------------------------------------ grammar

    private fun grammarCreated(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        if (exists("grammar_point", id)) return none()
        val row = base(id, h)
        row.put("title", p.reqStr("title")); stamp(row, "title", h)
        val defaults: List<Pair<String, JsonValue>> = listOf(
            "pattern" to JsonString(""), "meaning" to JsonString(""), "explanation" to JsonString(""), "notes" to JsonString(""),
            "jlpt" to JsonNull, "detection_rules" to JsonArray(emptyList()), "related_ids" to JsonArray(emptyList()),
            "links" to JsonArray(emptyList()), "is_stub" to JsonBool(false),
        )
        for ((c, d) in defaults) {
            val v = p.members[c] ?: d
            row.set<JsonNode>(c, SyncJson.toJackson(v))
            if (v != d) stamp(row, c, h)
        }
        rows.insert("grammar_point", row)
        return none()
    }

    private fun span(p: JsonObject, h: String, removed: Boolean): List<FollowUp> {
        val row = SyncJson.obj()
        row.put("sentence_id", p.reqStr("sentence_id"))
        row.put("grammar_point_id", p.reqStr("grammar_point_id"))
        row.put("start_offset", p.reqLong("start_offset"))
        row.put("end_offset", p.reqLong("end_offset"))
        row.put("origin", "manual")
        row.set<JsonNode>("media_id", if (removed) n.nullNode() else p.node("media_id"))
        row.putNull("rule_index")
        row.put("created_at", SyncJson.iso(wall(h)))
        row.set<JsonNode>("deleted_at", if (removed) instant(wall(h)) else n.nullNode())
        row.put("hlc", h)
        wholeRow("sentence_grammar_span", listOf("sentence_id", "grammar_point_id", "start_offset", "end_offset", "origin"), row, h)
        return none()
    }

    // ------------------------------------------------------------------ settings

    private fun settings(p: JsonObject, h: String): List<FollowUp> {
        val key = p.reqStr("key")
        // JSON null is a stored value ("use the default"), so the value goes in as jsonb text, never via populate_record.
        val value = DefaultCanonicalJson.encode(p.members["value"] ?: JsonNull)
        val at = SyncJson.iso(wall(h))
        val stored = dsl.fetchOptional("select hlc::text from app.settings where key = ?", key).map { it.get(0, String::class.java) }.orElse(null)
        when {
            stored == null -> dsl.execute("insert into app.settings (key, value, hlc, updated_at) values (?, ?::jsonb, ?, ?::timestamptz)", key, value, h, at)
            h > stored -> dsl.execute("update app.settings set value = ?::jsonb, hlc = ?, updated_at = ?::timestamptz where key = ?", value, h, at, key)
        }
        return none()
    }

    // ------------------------------------------------------------------ fsrs_params

    private fun presetCreated(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        if (exists("fsrs_params", id)) return none()
        val row = base(id, h)
        row.put("name", p.reqStr("name")); stamp(row, "name", h)
        for ((c, d) in PRESET_DEFAULTS) {
            val v = p.members[c] ?: continue // omitted: the column default, no clock
            row.set<JsonNode>(c, SyncJson.toJackson(v))
            if (v != d) stamp(row, c, h)
        }
        rows.insert("fsrs_params", row)
        return none()
    }

    private fun presetPublished(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        val ed = editor("fsrs_params", id)
        if (p.reqLong("weights_version") <= ed.row["weights_version"].asLong()) return none()
        for (c in listOf("weights_version", "fsrs_version", "weights", "optimizer_stats")) ed.set(c, p.node(c))
        ed.set("optimized_at", instant(p.long("optimized_at")))
        ed.bumpUpdated(wall(h))
        rows.update("fsrs_params", ID, ed.row, ed.dirty)
        cache.presetChanged(dsl, UUID.fromString(id), PresetChange.PUBLISHED)
        return none()
    }

    // ------------------------------------------------------------------ media

    private fun mediaCreated(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        if (exists("media", id)) return none()
        val row = base(id, h)
        row.put("title", p.reqStr("title")); stamp(row, "title", h)
        row.put("kind", p.reqStr("kind")); stamp(row, "kind", h)
        row.put("source", p.reqStr("source"))
        row.set<JsonNode>("jpdb_id", p.node("jpdb_id"))
        row.set<JsonNode>("cover_blob_hash", p.node("cover_blob_hash"))
        if (p.member("cover_blob_hash") != null) stamp(row, "cover_blob_hash", h)
        rows.insert("media", row)
        // OPS.md: "The server emits the media's deck.created (id = media id) right after, with caused_by_op_id."
        val title = p.reqStr("title")
        val name = if (title.codePointCount(0, title.length) <= DECK_NAME_MAX) title else title.substring(0, title.offsetByCodePoints(0, DECK_NAME_MAX))
        val deck = JsonObject(
            linkedMapOf(
                "id" to JsonString(id), "name" to JsonString(name), "type" to JsonString("media"), "media_id" to JsonString(id),
            ),
        )
        return listOf(FollowUp(DECK_CREATED, deck))
    }

    /**
     * `media.deleted`: register `deleted`; on the first transition the server hard-deletes the
     * media's segments, sentences, occurrences and detected spans and emits `deck.deleted` for the
     * live media deck (OPS.md), as a follow-up logged after this op with `caused_by_op_id`.
     */
    private fun mediaDeleted(id: String, h: String): List<FollowUp> {
        if (!deleted("media", id, h)) return none()
        dsl.execute("delete from app.sentence_grammar_span where media_id = ?::uuid and origin = 'detected'", id)
        dsl.execute("delete from app.occurrence where media_id = ?::uuid", id)
        dsl.execute("delete from app.sentence where media_id = ?::uuid", id)
        dsl.execute("delete from app.media_segment where media_id = ?::uuid", id)
        val liveDeck = dsl.fetchExists(dsl.selectOne().from("app.deck").where("id = ?::uuid and deleted_at is null", id))
        return if (liveDeck) listOf(FollowUp(DECK_DELETED, JsonObject(mapOf("id" to JsonString(id))))) else none()
    }

    // ------------------------------------------------------------------ parse_override (server only)

    private fun parseOverridePut(p: JsonObject, h: String): List<FollowUp> {
        val id = p.reqStr("id")
        val w = wall(h)
        val existing = rows.load("parse_override", Rows.key("id" to id))
        val values = SyncJson.obj().apply {
            set<JsonNode>("media_id", p.node("media_id"))
            set<JsonNode>("sentence_id", p.node("sentence_id"))
            put("match_text", p.reqStr("match_text"))
            set<JsonNode>("tokens", p.node("tokens"))
            put("note", p.str("note") ?: "")
            put("hlc", h)
            put("updated_at", SyncJson.iso(w))
        }
        if (existing == null) {
            values.put("id", id); values.put("created_at", SyncJson.iso(w))
            rows.insert("parse_override", values)
            return none()
        }
        val ed = RowEditor(existing)
        if (w < SyncJson.reqMs(existing["created_at"])) ed.set("created_at", n.textNode(SyncJson.iso(w)))
        if (h > existing["hlc"].asText()) values.fields().forEach { (k, v) -> ed.set(k, v) }
        rows.update("parse_override", ID, ed.row, ed.dirty)
        return none()
    }

    private fun parseOverrideDeleted(id: String, h: String): List<FollowUp> {
        val ed = editor("parse_override", id)
        val w = wall(h)
        val cur = SyncJson.ms(ed.row["deleted_at"])
        if (cur == null || w < cur) ed.set("deleted_at", n.textNode(SyncJson.iso(w)))
        if (h > ed.row["hlc"].asText()) {
            ed.set("hlc", n.textNode(h)); ed.set("updated_at", n.textNode(SyncJson.iso(w)))
        }
        rows.update("parse_override", ID, ed.row, ed.dirty)
        return none()
    }

    companion object {
        val ID = listOf("id")

        /** `deck.created` `name` maxLength (code points); a media title may be longer (500). */
        const val DECK_NAME_MAX = 200
        val SCHEDULE_FEEDING = setOf("scheduled", "learning", "manual")

        /** `fsrs_params` column defaults (V1), compared by JSON equality for the creation rule. */
        val PRESET_DEFAULTS: Map<String, JsonValue> = mapOf(
            "desired_retention" to JsonNumber(0.9),
            "maximum_interval_days" to JsonNumber(36500.0),
            "learning_steps_s" to JsonArray(listOf(JsonNumber(60.0), JsonNumber(600.0))),
            "relearning_steps_s" to JsonArray(listOf(JsonNumber(600.0))),
            "fuzz" to JsonBool(true),
            "easy_days" to JsonArray(List(7) { JsonNumber(1.0) }),
        )

        /** 7.2: `null`, `""`, `[]`, `{}` are dropped from JSON-object columns at creation. */
        fun isEmpty(v: JsonValue): Boolean = v == JsonNull || v == JsonString("") || v == JsonArray(emptyList()) || v == JsonObject(emptyMap())
    }
}
