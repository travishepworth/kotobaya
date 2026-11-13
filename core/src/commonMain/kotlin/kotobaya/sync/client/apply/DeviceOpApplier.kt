package kotobaya.sync.client.apply

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType.*
import kotobaya.sync.DefaultHlcRules
import kotobaya.sync.client.store.ApplyOrigin
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.StoreTransaction

/**
 * The device's apply function (SYNC.md 7–9, OPS.md "Apply"), the same for the local effect of a
 * minted op, for pulled ops and for the re-apply step of a rebuild (SYNC.md 4 step 2, 10.3 step 3).
 * It follows the server's `OpApplier` (T202b) rule for rule, minus what only the server does (the 8.1
 * merge trigger and its emitted ops, `parse_override`, follow-up ops of `media.*`):
 *
 * - **7.1 clock rule** per register (`fields.<key>`, `settings.<key>`, `templates.<kind>` per key);
 *   `updated_at = max(updated_at, wall(h))` on every winning write.
 * - **7.2 creation rule**: an existing id is a no-op; registers are stamped iff their initial value
 *   differs from the column default; empty keys of JSON-object columns are dropped.
 * - **7.3 tombstones**: register `deleted`; deletion is permanent; writes to tombstones still apply.
 * - **7.4 whole-row values** (`settings`, `word_status` override, `deck_item`, manual spans,
 *   `confusable` by rank then hlc), **7.6** server-written versions (`>`, `>=` for media).
 * - **7.7 missing targets**: an op whose target or a required parent is absent is a no-op, reported
 *   to [onMissing] (it cannot happen in `seq` order; it can in a rebuild's re-apply step).
 * - **7.8 idempotency**: applying an op twice changes nothing.
 * - **7.9 `review_log` append-only**, enforced in code: every write goes through
 *   [AppendOnlyReviewLog] and the tables the apply code sees are [AppendOnlyReviewLog.guard]ed.
 * - **8.2 / 8.3 merges**: `note.merged` / `card.merged` forward the loser's clocked registers, tombstone
 *   and flatten; later register writes to a loser are applied to it and forwarded to its survivor.
 * - **9 replay** in the same transaction through [cache] (frozen `ReplayEngine` contract only):
 *   fold or replay after `review.logged`, replay after a not-undone→undone `review.undone`,
 *   `card.merged`, a winning `card.preset_set`, preset changes. A refused replay writes the refused
 *   cache and never throws (9.4).
 *
 * Before applying, [apply] makes sure the startup replay of SYNC.md 10.5 has committed for the
 * running core ([StartupReplay.ensure]), so nothing is folded into a cache of another core version.
 */
public class DeviceOpApplier<L : LearningTables>(
    /** The card cache (replay engine, diagnostics). */
    public val cache: DeviceCardCache = DeviceCardCache(),
    private val startup: StartupReplay = StartupReplay(cache),
    /** Called for each op that was a no-op because a row it needs is missing (SYNC.md 7.7): the op and what is missing. */
    private val onMissing: (Op, String) -> Unit = { _, _ -> },
) : OpApplier<L> {
    override fun apply(tx: StoreTransaction<L>, op: Op, origin: ApplyOrigin) {
        startup.ensure(tx)
        applyTo(tx.learning, op)
    }

    /**
     * Applies [op] to [tables] (inside the caller's transaction). Returns false iff the op was a
     * no-op because a referenced row is missing (7.7). Does not run the startup check.
     */
    public fun applyTo(tables: LearningTables, op: Op): Boolean {
        val t = AppendOnlyReviewLog.guard(tables)
        missingReference(t, op)?.let {
            onMissing(op, it)
            return false
        }
        val before = AutoStatus.entriesOf(t, op)
        Apply(t, AppendOnlyReviewLog(AppendOnlyReviewLog.unguarded(t)), cache, op).run()
        AutoStatus.rederive(t, before + AutoStatus.entriesOf(t, op), wall(op.hlc))
        return true
    }

    /** Statics. */
    public companion object {
        /**
         * The first row [op] needs that [tables] lacks, as `"<table> <id>"`, or null. These are the
         * rows whose absence makes the server answer `unknown_reference` (OPS.md "Server rejects",
         * `tools/model.py` `refs`), plus both rows of a merge and the target of a version op.
         */
        public fun missingReference(tables: LearningTables, op: Op): String? {
            for ((table, id) in references(op)) {
                if (tables.get(table, listOf(JsonString(id))) == null) return "${table.sqlName} $id"
            }
            return null
        }

        private fun references(op: Op): List<Pair<LearningTable, String>> {
            val p = op.payload.members
            fun s(k: String): String? = (p[k] as? JsonString)?.value
            val out = ArrayList<Pair<LearningTable, String>>()
            fun need(table: LearningTable, k: String) {
                s(k)?.let { out += table to it }
            }
            val own = LearningTable.entries.firstOrNull { it.sqlName == op.entityType }
            when (op.opType) {
                NOTE_TYPE_CREATED, DECK_CREATED, GRAMMAR_CREATED, FSRS_PARAMS_CREATED, MEDIA_CREATED -> Unit
                NOTE_CREATED -> {
                    need(LearningTable.NOTE_TYPE, "note_type_id")
                    need(LearningTable.GRAMMAR_POINT, "grammar_point_id")
                }
                CARD_CREATED -> {
                    need(LearningTable.NOTE, "note_id")
                    out += LearningTable.FSRS_PARAMS to (s("preset_id") ?: Reserved.DEFAULT_PRESET_ID)
                }
                CARD_PRESET_SET -> {
                    need(LearningTable.CARD, "id")
                    need(LearningTable.FSRS_PARAMS, "preset_id")
                }
                NOTE_MERGED -> {
                    need(LearningTable.NOTE, "loser_id")
                    need(LearningTable.NOTE, "survivor_id")
                }
                CARD_MERGED -> {
                    need(LearningTable.CARD, "loser_id")
                    need(LearningTable.CARD, "survivor_id")
                }
                REVIEW_LOGGED -> need(LearningTable.CARD, "card_id")
                REVIEW_UNDONE -> need(LearningTable.REVIEW_LOG, "review_id")
                DECK_ITEM_PUT, DECK_ITEM_REMOVED -> {
                    need(LearningTable.DECK, "deck_id")
                    s("item_key")?.takeIf { it.startsWith("n:") }?.let { out += LearningTable.NOTE to it.substring(2) }
                }
                DECK_CONTENT_PUBLISHED -> need(LearningTable.DECK, "deck_id")
                MEDIA_CONTENT_PUBLISHED -> need(LearningTable.MEDIA, "media_id")
                CONFUSABLE_SUGGESTED, CONFUSABLE_CONFIRMED, CONFUSABLE_REJECTED -> {
                    need(LearningTable.NOTE, "note_a_id")
                    need(LearningTable.NOTE, "note_b_id")
                }
                GRAMMAR_SPAN_PUT, GRAMMAR_SPAN_REMOVED -> need(LearningTable.GRAMMAR_POINT, "grammar_point_id")
                WORD_STATUS_SET, WORD_STATUS_CLEARED, SETTINGS_SET, PARSE_OVERRIDE_PUT, PARSE_OVERRIDE_DELETED -> Unit
                else -> if (own != null) need(own, "id")
            }
            return out
        }
    }
}

/** The clock rule (SYNC.md 7.1) on one loaded row; [build] gives the row to write back. */
internal class RowEdit(row: Row) {
    val row: LinkedHashMap<String, JsonValue> = LinkedHashMap(row)
    private val clocks = LinkedHashMap((row["clocks"] as? JsonObject)?.members ?: emptyMap())

    fun clockOf(name: String): String? = (clocks[name] as? JsonString)?.value

    fun clockNames(): List<String> = clocks.keys.sorted()

    fun stamp(name: String, h: String) {
        clocks[name] = JsonString(h)
    }

    operator fun get(col: String): JsonValue = row[col] ?: JsonNull

    fun set(col: String, v: JsonValue) {
        row[col] = v
    }

    fun setKey(col: String, key: String, v: JsonValue?) {
        val o = LinkedHashMap((row[col] as? JsonObject)?.members ?: emptyMap())
        if (v == null || v == JsonNull) o.remove(key) else o[key] = v
        row[col] = JsonObject(o)
    }

    fun register(name: String, h: String, write: (RowEdit) -> Unit): Boolean {
        val cur = clockOf(name)
        if (cur != null && h <= cur) return false
        clocks[name] = JsonString(h)
        write(this)
        bumpUpdated(wall(h))
        return true
    }

    fun bumpUpdated(at: Long) {
        val cur = (row["updated_at"] as? JsonNumber)?.value?.toLong() ?: Long.MIN_VALUE
        if (at > cur) row["updated_at"] = instant(at)
    }

    fun build(): Row {
        row["clocks"] = JsonObject(LinkedHashMap(clocks))
        return row
    }
}

/** A register write from a payload or copied from a merge loser: the register name and how to write its value. */
internal class RegWrite(val name: String, val write: (RowEdit) -> Unit)

internal fun wall(h: String): Long = DefaultHlcRules.wall(h)

internal fun instant(v: Long): JsonValue = JsonNumber(v.toDouble())

/** One op's application. */
private class Apply(
    private val t: LearningTables,
    private val reviews: AppendOnlyReviewLog,
    private val cache: DeviceCardCache,
    private val op: Op,
) {
    private val p: Map<String, JsonValue> = op.payload.members
    private val h: String = op.hlc

    fun run() {
        when (op.opType) {
            NOTE_TYPE_CREATED -> noteTypeCreated()
            NOTE_TYPE_UPDATED -> registers(LearningTable.NOTE_TYPE, str("id"), setWrites(obj("set"), setOf("templates")))
            NOTE_TYPE_DELETED -> deleted(LearningTable.NOTE_TYPE, str("id"))
            NOTE_CREATED -> noteCreated()
            NOTE_UPDATED -> registers(LearningTable.NOTE, str("id"), setWrites(obj("set"), setOf("fields")))
            NOTE_DELETED -> deleted(LearningTable.NOTE, str("id"))
            NOTE_MERGED -> merged(LearningTable.NOTE, str("loser_id"), str("survivor_id"))
            CARD_CREATED -> cardCreated()
            CARD_SUSPENDED -> registers(LearningTable.CARD, str("id"), listOf(RegWrite("suspended") { it.set("suspended", JsonBool(true)) }))
            CARD_UNSUSPENDED -> registers(LearningTable.CARD, str("id"), listOf(RegWrite("suspended") { it.set("suspended", JsonBool(false)) }))
            CARD_BURIED -> registers(LearningTable.CARD, str("id"), listOf(RegWrite("buried_until") { it.set("buried_until", node("until")) }))
            CARD_UNBURIED -> registers(LearningTable.CARD, str("id"), listOf(RegWrite("buried_until") { it.set("buried_until", JsonNull) }))
            CARD_FLAG_SET -> registers(LearningTable.CARD, str("id"), listOf(RegWrite("flags") { it.set("flags", node("flags")) }))
            CARD_PRESET_SET -> {
                val won = registers(LearningTable.CARD, str("id"), listOf(RegWrite("preset_id") { it.set("preset_id", node("preset_id")) }))
                cache.replayCards(t, won)
            }
            CARD_DELETED -> deleted(LearningTable.CARD, str("id"))
            CARD_MERGED -> merged(LearningTable.CARD, str("loser_id"), str("survivor_id"))?.let { cache.replayCards(t, listOf(it)) }
            REVIEW_LOGGED -> reviewLogged()
            REVIEW_UNDONE -> reviewUndone()
            WORD_STATUS_SET -> wordStatus((node("status") as? JsonString)?.value, (p["origin"] as? JsonString)?.value ?: "manual")
            WORD_STATUS_CLEARED -> wordStatus(null, null)
            DECK_CREATED -> deckCreated()
            DECK_UPDATED -> registers(LearningTable.DECK, str("id"), setWrites(obj("set"), setOf("settings")))
            DECK_DELETED -> deleted(LearningTable.DECK, str("id"))
            DECK_CONTENT_PUBLISHED -> published(LearningTable.DECK, str("deck_id"), emptyList(), allowEqual = false)
            DECK_ITEM_PUT -> deckItem(removed = false)
            DECK_ITEM_REMOVED -> deckItem(removed = true)
            CONFUSABLE_SUGGESTED -> confusable("suggested")
            CONFUSABLE_CONFIRMED -> confusable("confirmed")
            CONFUSABLE_REJECTED -> confusable("rejected")
            GRAMMAR_CREATED -> grammarCreated()
            GRAMMAR_UPDATED -> registers(LearningTable.GRAMMAR_POINT, str("id"), setWrites(obj("set")))
            GRAMMAR_DELETED -> deleted(LearningTable.GRAMMAR_POINT, str("id"))
            GRAMMAR_SPAN_PUT -> span(removed = false)
            GRAMMAR_SPAN_REMOVED -> span(removed = true)
            SETTINGS_SET -> settings()
            FSRS_PARAMS_CREATED -> presetCreated()
            FSRS_PARAMS_UPDATED -> {
                val won = registerNames(LearningTable.FSRS_PARAMS, str("id"), setWrites(obj("set")))
                if (won.any { it != "name" }) cache.replayEffectivePreset(t, str("id"))
            }
            FSRS_PARAMS_DELETED -> if (deleted(LearningTable.FSRS_PARAMS, str("id"))) cache.replayPresetCards(t, str("id"))
            FSRS_PARAMS_PUBLISHED -> presetPublished()
            MEDIA_CREATED -> mediaCreated()
            MEDIA_UPDATED -> registers(LearningTable.MEDIA, str("id"), setWrites(obj("set")))
            MEDIA_DELETED -> mediaDeleted()
            MEDIA_CONTENT_PUBLISHED -> published(LearningTable.MEDIA, str("media_id"), listOf("parse_status", "parse_error"), allowEqual = true)
            PARSE_OVERRIDE_PUT, PARSE_OVERRIDE_DELETED -> Unit // devices do nothing (OPS.md; device_applies = false)
        }
    }

    // ------------------------------------------------------------------ payload access

    private fun node(k: String): JsonValue = p[k] ?: JsonNull

    private fun str(k: String): String = (p[k] as? JsonString)?.value ?: error("${op.opType.wire}: payload.$k is not a string")

    private fun obj(k: String): JsonObject = p[k] as? JsonObject ?: error("${op.opType.wire}: payload.$k is not an object")

    private fun key(id: String): List<JsonValue> = listOf(JsonString(id))

    private fun load(table: LearningTable, id: String): RowEdit? = t.get(table, key(id))?.let(::RowEdit)

    private fun base(id: String): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "id" to JsonString(id),
        "created_at" to instant(wall(h)),
        "updated_at" to instant(wall(h)),
        "deleted_at" to JsonNull,
    )

    // ------------------------------------------------------------------ generic pieces

    /**
     * Register writes for a `*.updated` `set`: one per member; members named in [jsonKeyed] are JSON
     * object columns with one register per key (null removes the key, the clock stays). A note's
     * `source_sentence` register covers its three columns (OPS.md).
     */
    private fun setWrites(set: JsonObject, jsonKeyed: Set<String> = emptySet()): List<RegWrite> = buildList {
        for ((name, value) in set.members) {
            when {
                name in jsonKeyed -> (value as JsonObject).members.forEach { (k, v) -> add(RegWrite("$name.$k") { it.setKey(name, k, v) }) }
                name == "source_sentence_id" || name == "source_media_id" -> Unit // part of source_sentence
                name == "source_sentence" -> add(
                    RegWrite("source_sentence") { e ->
                        for (c in SOURCE_COLUMNS) e.set(c, set.members[c] ?: JsonNull)
                    },
                )
                else -> add(RegWrite(name) { it.set(name, value) })
            }
        }
    }

    /**
     * Writes [writes] to row [id] of [table] by the clock rule and, if the row is a merge loser, to its
     * survivor too (8.3; not `deleted` / `entry_seq`). Returns the ids of maintained cards on which a
     * write won (for `card.preset_set`).
     */
    private fun registers(table: LearningTable, id: String, writes: List<RegWrite>): List<String> {
        val ed = load(table, id) ?: return emptyList()
        val won = writes.count { ed.register(it.name, h, it.write) } > 0
        t.put(table, ed.build())
        val out = ArrayList<String>()
        if (won) out += id
        val target = (ed["merged_into_id"] as? JsonString)?.value
        if (target != null) {
            val fw = writes.filter { it.name !in notForwarded(table) }
            val s = load(table, target)
            if (fw.isNotEmpty() && s != null) {
                if (fw.count { s.register(it.name, h, it.write) } > 0) out += target
                t.put(table, s.build())
            }
        }
        return out.filter { cid -> t.get(table, key(cid))?.let(DeviceCardCache::isMaintained) == true }
    }

    /** Like [registers] on a table without merges; returns the register names that won. */
    private fun registerNames(table: LearningTable, id: String, writes: List<RegWrite>): List<String> {
        val ed = load(table, id) ?: return emptyList()
        val won = writes.filter { ed.register(it.name, h, it.write) }.map { it.name }
        t.put(table, ed.build())
        return won
    }

    /** Register `deleted` (7.3; never forwarded). Returns true on the first transition to tombstoned. */
    private fun deleted(table: LearningTable, id: String): Boolean {
        val ed = load(table, id) ?: return false
        val wasLive = ed["deleted_at"] == JsonNull
        ed.register("deleted", h) { it.set("deleted_at", instant(wall(h))) }
        t.put(table, ed.build())
        return wasLive && ed["deleted_at"] != JsonNull
    }

    private fun notForwarded(table: LearningTable): Set<String> =
        if (table == LearningTable.NOTE) setOf("deleted", "entry_seq") else setOf("deleted")

    /** Value of register [reg] in [row], as a write (merge forwarding, 8.2 step 1). */
    private fun copyOf(row: RowEdit, reg: String): RegWrite = when {
        "." in reg -> {
            val col = reg.substringBefore('.')
            val k = reg.substringAfter('.')
            RegWrite(reg) { it.setKey(col, k, (row[col] as? JsonObject)?.members?.get(k)) }
        }
        reg == "source_sentence" -> RegWrite(reg) { e -> for (c in SOURCE_COLUMNS) e.set(c, row[c]) }
        else -> RegWrite(reg) { it.set(reg, row[reg]) }
    }

    /** `note.merged` / `card.merged` (8.2). Returns the survivor actually used, or null when the op was a no-op. */
    private fun merged(table: LearningTable, loserId: String, survivorId: String): String? {
        val loser = load(table, loserId) ?: return null
        if (loser["merged_into_id"] != JsonNull) return null
        var survivor = load(table, survivorId) ?: return null
        (survivor["merged_into_id"] as? JsonString)?.value?.let { survivor = load(table, it) ?: return null }
        val wId = (survivor["id"] as JsonString).value
        if (wId == loserId) return null
        for (reg in loser.clockNames()) {
            if (reg in notForwarded(table)) continue
            survivor.register(reg, checkNotNull(loser.clockOf(reg)), copyOf(loser, reg).write)
        }
        t.put(table, survivor.build())
        if (loser["deleted_at"] == JsonNull) loser.set("deleted_at", instant(wall(h)))
        loser.set("merged_into_id", JsonString(wId))
        loser.bumpUpdated(wall(h))
        t.put(table, loser.build())
        for (row in t.where(table, "merged_into_id", JsonString(loserId))) {
            t.put(table, LinkedHashMap(row).also { it["merged_into_id"] = JsonString(wId) })
        }
        return wId
    }

    /** 7.2: `null`, `""`, `[]`, `{}` are dropped from JSON-object columns at creation. */
    private fun isEmpty(v: JsonValue): Boolean =
        v == JsonNull || v == JsonString("") || v == JsonArray(emptyList()) || v == JsonObject(emptyMap())

    /** A creation-time JSON-object column: non-empty keys kept and stamped `<col>.<key>`. */
    private fun createObject(row: LinkedHashMap<String, JsonValue>, clocks: RowEdit, col: String, value: JsonValue?) {
        val kept = LinkedHashMap<String, JsonValue>()
        (value as? JsonObject)?.members?.forEach { (k, v) ->
            if (!isEmpty(v)) {
                kept[k] = v
                clocks.stamp("$col.$k", h)
            }
        }
        row[col] = JsonObject(kept)
    }

    /** Inserts a created row: [cols] with their values, stamping each register whose value differs from its default. */
    private fun insertCreated(table: LearningTable, row: LinkedHashMap<String, JsonValue>, stamps: RowEdit) {
        row["clocks"] = JsonObject(emptyMap())
        val ed = RowEdit(row)
        for (name in stamps.clockNames()) ed.stamp(name, checkNotNull(stamps.clockOf(name)))
        t.put(table, ed.build())
    }

    private fun exists(table: LearningTable, id: String): Boolean = t.get(table, key(id)) != null

    // ------------------------------------------------------------------ note_type

    private fun noteTypeCreated() {
        val id = str("id")
        if (exists(LearningTable.NOTE_TYPE, id)) return
        val st = RowEdit(emptyMap())
        val row = base(id)
        row["name"] = node("name"); st.stamp("name", h)
        row["base_type"] = node("base_type")
        row["builtin"] = JsonBool(false)
        row["fields"] = node("fields"); st.stamp("fields", h)
        createObject(row, st, "templates", p["templates"])
        val css = (p["css"] as? JsonString)?.value ?: ""
        row["css"] = JsonString(css)
        if (css.isNotEmpty()) st.stamp("css", h)
        insertCreated(LearningTable.NOTE_TYPE, row, st)
    }

    // ------------------------------------------------------------------ note

    private fun noteCreated() {
        val id = str("id")
        if (exists(LearningTable.NOTE, id)) return
        val nt = checkNotNull(t.get(LearningTable.NOTE_TYPE, key(str("note_type_id"))))
        val st = RowEdit(emptyMap())
        val row = base(id)
        row["note_type_id"] = node("note_type_id")
        row["type"] = nt["base_type"] ?: JsonNull
        row["language"] = p["language"] ?: JsonString("ja")
        row["entry_seq"] = node("entry_seq")
        if (node("entry_seq") != JsonNull) st.stamp("entry_seq", h)
        row["kanji_literal"] = node("kanji_literal")
        row["grammar_point_id"] = node("grammar_point_id")
        createObject(row, st, "fields", p["fields"])
        val tags = p["tags"] ?: JsonArray(emptyList())
        row["tags"] = tags
        if (tags != JsonArray(emptyList())) st.stamp("tags", h)
        val mnemonic = p["mnemonic"] ?: JsonString("")
        row["mnemonic"] = mnemonic
        if (mnemonic != JsonString("")) st.stamp("mnemonic", h)
        row["source_sentence"] = p["source_sentence"] ?: JsonString("")
        row["source_sentence_id"] = node("source_sentence_id")
        row["source_media_id"] = node("source_media_id")
        if (row["source_sentence"] != JsonString("") || row["source_sentence_id"] != JsonNull || row["source_media_id"] != JsonNull) {
            st.stamp("source_sentence", h)
        }
        row["origin"] = p["origin"] ?: JsonString("manual")
        row["created_by_device_id"] = JsonString(op.deviceId)
        row["merged_into_id"] = JsonNull
        insertCreated(LearningTable.NOTE, row, st)
    }

    // ------------------------------------------------------------------ card

    private fun cardCreated() {
        val id = str("id")
        if (exists(LearningTable.CARD, id)) return
        val st = RowEdit(emptyMap())
        val row = base(id)
        row["note_id"] = node("note_id")
        row["kind"] = node("kind")
        val preset = p["preset_id"] ?: JsonString(Reserved.DEFAULT_PRESET_ID)
        row["preset_id"] = preset
        if (preset != JsonString(Reserved.DEFAULT_PRESET_ID)) st.stamp("preset_id", h)
        val suspended = p["suspended"] ?: JsonBool(false)
        row["suspended"] = suspended
        if (suspended != JsonBool(false)) st.stamp("suspended", h)
        row["buried_until"] = node("buried_until")
        if (node("buried_until") != JsonNull) st.stamp("buried_until", h)
        val flags = p["flags"] ?: JsonNumber(0.0)
        row["flags"] = flags
        if (flags != JsonNumber(0.0)) st.stamp("flags", h)
        row["state"] = JsonString("new")
        for (c in listOf("step", "stability", "difficulty", "due_at", "interval_days", "last_review_at", "last_review_id")) row[c] = JsonNull
        row["reps"] = JsonNumber(0.0)
        row["lapses"] = JsonNumber(0.0)
        row["weights_version"] = JsonNumber(0.0)
        row["merged_into_id"] = JsonNull
        insertCreated(LearningTable.CARD, row, st)
    }

    // ------------------------------------------------------------------ review_log

    private fun targetCard(cardId: String): String =
        (t.get(LearningTable.CARD, key(cardId))?.get("merged_into_id") as? JsonString)?.value ?: cardId

    private fun reviewLogged() {
        val row = linkedMapOf<String, JsonValue>(
            "id" to node("id"),
            "card_id" to node("card_id"),
            "device_id" to JsonString(op.deviceId),
            "hlc" to (p["replay_hlc"]?.takeUnless { it == JsonNull } ?: JsonString(h)),
        )
        for (c in REVIEW_COLUMNS) row[c] = node(c)
        row["undone_at"] = JsonNull
        row["undone_hlc"] = JsonNull
        if (!reviews.insert(row)) return // 7.9 rule 1: the first write wins
        cache.reviewLogged(t, row, targetCard(str("card_id")))
    }

    private fun reviewUndone() {
        val id = str("review_id")
        val outcome = reviews.undo(id, h) ?: return
        if (outcome.replayTarget) {
            val cardId = (reviews.get(id)?.get("card_id") as? JsonString)?.value ?: return
            cache.replayCards(t, listOf(targetCard(cardId)))
        }
    }

    // ------------------------------------------------------------------ word_status

    private fun wordStatus(status: String?, origin: String?) {
        val seq = node("entry_seq")
        val existing = t.get(LearningTable.WORD_STATUS, listOf(seq))
        val row = LinkedHashMap(
            existing ?: linkedMapOf(
                "entry_seq" to seq, "status" to JsonString("new"), "auto_status" to JsonNull, "override_status" to JsonNull,
                "override_hlc" to JsonNull, "override_origin" to JsonNull, "updated_at" to instant(wall(h)),
            ),
        )
        val cur = (row["override_hlc"] as? JsonString)?.value
        if (cur != null && h <= cur) return
        row["override_status"] = status?.let(::JsonString) ?: JsonNull
        row["override_origin"] = origin?.let(::JsonString) ?: JsonNull
        row["override_hlc"] = JsonString(h)
        row["status"] = JsonString(status ?: (row["auto_status"] as? JsonString)?.value ?: "new")
        val upd = (row["updated_at"] as? JsonNumber)?.value?.toLong() ?: Long.MIN_VALUE
        if (wall(h) > upd) row["updated_at"] = instant(wall(h))
        t.put(LearningTable.WORD_STATUS, row)
    }

    // ------------------------------------------------------------------ deck

    private fun deckCreated() {
        val id = str("id")
        if (exists(LearningTable.DECK, id)) return
        val st = RowEdit(emptyMap())
        val row = base(id)
        row["name"] = node("name"); st.stamp("name", h)
        row["type"] = node("type")
        row["language"] = p["language"] ?: JsonString("ja")
        val priority = p["priority"] ?: JsonNumber(0.0)
        row["priority"] = priority
        if (priority != JsonNumber(0.0)) st.stamp("priority", h)
        val enabled = p["enabled"] ?: JsonBool(true)
        row["enabled"] = enabled
        if (enabled != JsonBool(true)) st.stamp("enabled", h)
        for (c in listOf("new_cards_per_day", "reviews_per_day", "query")) {
            row[c] = node(c)
            if (node(c) != JsonNull) st.stamp(c, h)
        }
        createObject(row, st, "settings", p["settings"])
        row["media_id"] = node("media_id")
        row["list_key"] = node("list_key")
        row["content_version"] = JsonNumber(0.0)
        row["content_blob_hash"] = JsonNull
        row["installed_content_version"] = JsonNumber(0.0)
        insertCreated(LearningTable.DECK, row, st)
    }

    /** `deck.content_published` (>) / `media.content_published` (>=, 7.6). */
    private fun published(table: LearningTable, id: String, extra: List<String>, allowEqual: Boolean) {
        val ed = load(table, id) ?: return
        val stored = (ed["content_version"] as? JsonNumber)?.value ?: 0.0
        val v = (node("content_version") as JsonNumber).value
        if (v > stored || (allowEqual && v == stored)) {
            for (c in listOf("content_version", "content_blob_hash") + extra) ed.set(c, node(c))
            ed.bumpUpdated(wall(h))
            t.put(table, ed.build())
        }
    }

    /** Whole-row LWW on `hlc` (7.4): replace every op-written column iff no row or a greater clock. */
    private fun wholeRow(table: LearningTable, row: Row) {
        val existing = t.get(table, table.keyOf(row))
        val stored = (existing?.get("hlc") as? JsonString)?.value
        if (existing == null || stored == null || h > stored) t.put(table, row)
    }

    private fun deckItem(removed: Boolean) {
        val itemKey = str("item_key")
        val row = linkedMapOf<String, JsonValue>("deck_id" to node("deck_id"), "item_key" to JsonString(itemKey))
        if (itemKey.startsWith("e:")) {
            row["entry_seq"] = JsonNumber(itemKey.substring(2).toLong().toDouble())
            row["note_id"] = JsonNull
        } else {
            row["entry_seq"] = JsonNull
            row["note_id"] = JsonString(itemKey.substring(2))
        }
        row["position"] = if (removed) JsonNumber(0.0) else node("position")
        row["occurrence_count"] = JsonNumber(0.0)
        row["added_at"] = instant(wall(h))
        row["deleted_at"] = if (removed) instant(wall(h)) else JsonNull
        row["hlc"] = JsonString(h)
        wholeRow(LearningTable.DECK_ITEM, row)
    }

    // ------------------------------------------------------------------ confusable

    private fun confusable(status: String) {
        val k = listOf(node("note_a_id"), node("note_b_id"), node("reason"))
        val existing = t.get(LearningTable.CONFUSABLE, k)
        val w = wall(h)
        if (existing == null) {
            t.put(
                LearningTable.CONFUSABLE,
                linkedMapOf(
                    "note_a_id" to k[0], "note_b_id" to k[1], "reason" to k[2], "status" to JsonString(status),
                    "hlc" to JsonString(h), "created_at" to instant(w), "updated_at" to instant(w),
                ),
            )
            return
        }
        val row = LinkedHashMap(existing)
        if (w < ((row["created_at"] as? JsonNumber)?.value?.toLong() ?: Long.MAX_VALUE)) row["created_at"] = instant(w)
        fun rank(s: String?) = if (s == "suggested") 0 else 1
        val r = rank(status)
        val sr = rank((row["status"] as? JsonString)?.value)
        if (r > sr || (r == sr && h > ((row["hlc"] as? JsonString)?.value ?: ""))) {
            row["status"] = JsonString(status)
            row["hlc"] = JsonString(h)
            row["updated_at"] = instant(w)
        }
        t.put(LearningTable.CONFUSABLE, row)
    }

    // ------------------------------------------------------------------ grammar

    private fun grammarCreated() {
        val id = str("id")
        if (exists(LearningTable.GRAMMAR_POINT, id)) return
        val st = RowEdit(emptyMap())
        val row = base(id)
        row["language"] = JsonString("ja")
        row["title"] = node("title"); st.stamp("title", h)
        for ((c, d) in GRAMMAR_DEFAULTS) {
            val v = p[c] ?: d
            row[c] = v
            if (v != d) st.stamp(c, h)
        }
        insertCreated(LearningTable.GRAMMAR_POINT, row, st)
    }

    private fun span(removed: Boolean) {
        val row = linkedMapOf(
            "sentence_id" to node("sentence_id"),
            "grammar_point_id" to node("grammar_point_id"),
            "start_offset" to node("start_offset"),
            "end_offset" to node("end_offset"),
            "origin" to JsonString("manual"),
            "media_id" to if (removed) JsonNull else node("media_id"),
            "rule_index" to JsonNull,
            "created_at" to instant(wall(h)),
            "deleted_at" to if (removed) instant(wall(h)) else JsonNull,
            "hlc" to JsonString(h),
        )
        wholeRow(LearningTable.SENTENCE_GRAMMAR_SPAN, row)
    }

    // ------------------------------------------------------------------ settings

    private fun settings() {
        wholeRow(
            LearningTable.SETTINGS,
            linkedMapOf("key" to node("key"), "value" to node("value"), "hlc" to JsonString(h), "updated_at" to instant(wall(h))),
        )
    }

    // ------------------------------------------------------------------ fsrs_params

    private fun presetCreated() {
        val id = str("id")
        if (exists(LearningTable.FSRS_PARAMS, id)) return
        val st = RowEdit(emptyMap())
        val row = BuiltIns.presetRow(id, str("name"), wall(h))
        st.stamp("name", h)
        for ((c, d) in BuiltIns.PRESET_DEFAULTS) {
            val v = p[c] ?: continue // omitted: the column default, no clock
            row[c] = v
            if (v != d) st.stamp(c, h)
        }
        insertCreated(LearningTable.FSRS_PARAMS, row, st)
    }

    private fun presetPublished() {
        val id = str("id")
        val ed = load(LearningTable.FSRS_PARAMS, id) ?: return
        val stored = (ed["weights_version"] as? JsonNumber)?.value ?: 0.0
        if ((node("weights_version") as JsonNumber).value <= stored) return
        for (c in listOf("weights_version", "fsrs_version", "weights", "optimizer_stats", "optimized_at")) ed.set(c, node(c))
        ed.bumpUpdated(wall(h))
        t.put(LearningTable.FSRS_PARAMS, ed.build())
        cache.replayEffectivePreset(t, id)
    }

    // ------------------------------------------------------------------ media

    private fun mediaCreated() {
        val id = str("id")
        if (exists(LearningTable.MEDIA, id)) return
        val st = RowEdit(emptyMap())
        val row = base(id)
        row["title"] = node("title"); st.stamp("title", h)
        row["kind"] = node("kind"); st.stamp("kind", h)
        row["language"] = JsonString("ja")
        row["source"] = node("source")
        row["jpdb_id"] = node("jpdb_id")
        row["cover_blob_hash"] = node("cover_blob_hash")
        if (node("cover_blob_hash") != JsonNull) st.stamp("cover_blob_hash", h)
        row["parse_status"] = JsonString("pending")
        row["parse_error"] = JsonNull
        row["content_version"] = JsonNumber(0.0)
        row["content_blob_hash"] = JsonNull
        row["installed_content_version"] = JsonNumber(0.0)
        insertCreated(LearningTable.MEDIA, row, st)
    }

    /**
     * `media.deleted` (OPS.md): register `deleted`; on the first transition the device deletes the
     * installed snapshot rows (segments, sentences, occurrences, detected spans) and sets
     * `installed_content_version = 0`. The media deck's `deck.deleted` arrives as its own op.
     */
    private fun mediaDeleted() {
        val id = str("id")
        if (!deleted(LearningTable.MEDIA, id)) return
        val mid = JsonString(id)
        for (r in t.where(LearningTable.SENTENCE_GRAMMAR_SPAN, "media_id", mid)) {
            if (r["origin"] == JsonString("detected")) t.delete(LearningTable.SENTENCE_GRAMMAR_SPAN, LearningTable.SENTENCE_GRAMMAR_SPAN.keyOf(r))
        }
        for (table in listOf(LearningTable.OCCURRENCE, LearningTable.SENTENCE, LearningTable.MEDIA_SEGMENT)) {
            for (r in t.where(table, "media_id", mid)) t.delete(table, table.keyOf(r))
        }
        val ed = checkNotNull(load(LearningTable.MEDIA, id))
        ed.set("installed_content_version", JsonNumber(0.0))
        t.put(LearningTable.MEDIA, ed.build())
    }

    private companion object {
        val SOURCE_COLUMNS = listOf("source_sentence", "source_sentence_id", "source_media_id")
        val REVIEW_COLUMNS = listOf(
            "reviewed_at", "utc_offset_min", "review_type", "rating", "button", "duration_ms",
            "manual_action", "manual_due_at", "sentence_id", "import_source",
        )
        val GRAMMAR_DEFAULTS: List<Pair<String, JsonValue>> = listOf(
            "pattern" to JsonString(""), "meaning" to JsonString(""), "explanation" to JsonString(""), "notes" to JsonString(""),
            "jlpt" to JsonNull, "detection_rules" to JsonArray(emptyList()), "related_ids" to JsonArray(emptyList()),
            "links" to JsonArray(emptyList()), "is_stub" to JsonBool(false),
        )
    }
}
