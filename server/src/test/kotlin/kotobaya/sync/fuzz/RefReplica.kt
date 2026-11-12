package kotobaya.sync.fuzz

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.Reserved
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.PresetRow
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler
import kotobaya.replay.DefaultReplayEngine
import java.util.TreeMap

/** The op references a row this replica does not have (cannot happen in seq order; SYNC.md 7.7). */
class MissingRow(what: String) : RuntimeException(what)

/** JSON as plain Kotlin values: `null`, Boolean, Long (integral numbers), Double, String, List, sorted Map. */
fun plain(n: JsonNode?): Any? = when {
    n == null || n.isNull || n.isMissingNode -> null
    n.isBoolean -> n.booleanValue()
    n.isIntegralNumber -> n.longValue()
    n.isNumber -> n.doubleValue().let { d -> if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) d.toLong() else d }
    n.isTextual -> n.textValue()
    n.isArray -> n.map { plain(it) }
    else -> TreeMap<String, Any?>().also { m -> n.fields().forEach { (k, v) -> m[k] = plain(v) } }
}

fun wallOf(h: String): Long = h.substring(0, 12).toLong(16)

/**
 * A device replica for the tests: a Kotlin port of `contracts/sync/tools/model.py` `Replica`,
 * restricted to the op types the fuzz generator and the supported scenario files use (note, card,
 * review). Independent of the server code on purpose: it is the oracle the server is compared with
 * (SYNC.md 11). Rows use the device representation (instants as epoch ms, arrays as lists).
 *
 * The card cache is not stored: [projection] computes it for every live non-loser card with a full
 * replay through the core's [ReplayEngine] (the cache is a pure function of the replay set, 9.3), so
 * a server that folds must agree with a replica that only ever replays.
 */
class RefReplica {
    val t: Map<String, MutableMap<String, MutableMap<String, Any?>>> = TABLES.associateWith { linkedMapOf() }

    init {
        for ((id, base) in BUILTIN_NOTE_TYPES) {
            t.getValue("note_type")[id] = mutableMapOf("id" to id, "base_type" to base, "deleted_at" to null, "clocks" to mutableMapOf<String, String>())
        }
        t.getValue("fsrs_params")[Reserved.DEFAULT_PRESET_ID] = mutableMapOf("id" to Reserved.DEFAULT_PRESET_ID, "deleted_at" to null)
    }

    fun copy(): RefReplica {
        val r = RefReplica()
        for ((table, rows) in t) {
            val dst = r.t.getValue(table)
            dst.clear()
            for ((k, row) in rows) dst[k] = deepCopy(row)
        }
        return r
    }

    private fun need(table: String, key: String): MutableMap<String, Any?> = t.getValue(table)[key] ?: throw MissingRow("$table $key")

    @Suppress("UNCHECKED_CAST")
    private fun clocks(row: MutableMap<String, Any?>) = row["clocks"] as MutableMap<String, String>

    @Suppress("UNCHECKED_CAST")
    private fun put(row: MutableMap<String, Any?>, reg: String, value: Any?, h: String) {
        when {
            "." in reg -> {
                val obj = row[reg.substringBefore('.')] as MutableMap<String, Any?>
                if (value == null) obj.remove(reg.substringAfter('.')) else obj[reg.substringAfter('.')] = deepCopy(value)
            }
            reg == "deleted" -> row["deleted_at"] = wallOf(h)
            reg == "source_sentence" -> {
                val v = value as List<Any?>
                row["source_sentence"] = v[0]; row["source_sentence_id"] = v[1]; row["source_media_id"] = v[2]
            }
            else -> row[reg] = deepCopy(value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun get(row: Map<String, Any?>, reg: String): Any? = when {
        "." in reg -> (row[reg.substringBefore('.')] as Map<String, Any?>)[reg.substringAfter('.')]
        reg == "source_sentence" -> listOf(row["source_sentence"], row["source_sentence_id"], row["source_media_id"])
        else -> row[reg]
    }

    /** The clock rule (7.1). */
    private fun write(row: MutableMap<String, Any?>, reg: String, value: Any?, h: String): Boolean {
        val cur = clocks(row)[reg]
        if (cur != null && h <= cur) return false
        put(row, reg, value, h)
        clocks(row)[reg] = h
        row["updated_at"] = maxOf(row["updated_at"] as Long, wallOf(h))
        return true
    }

    /** A register write addressed to a row, forwarded to its survivor (8.3). */
    private fun regWrite(table: String, key: String, reg: String, value: Any?, h: String) {
        val row = need(table, key)
        write(row, reg, value, h)
        val target = row["merged_into_id"] as String?
        if (target != null && reg !in notForwarded(table)) write(need(table, target), reg, value, h)
    }

    private fun notForwarded(table: String) = if (table == "note") setOf("entry_seq", "deleted") else setOf("deleted")

    private fun create(table: String, key: String, fixed: Map<String, Any?>, regs: List<Triple<String, Any?, Any?>>, objs: Map<String, Map<String, Any?>>, h: String) {
        if (key in t.getValue(table)) return
        val row = LinkedHashMap<String, Any?>(fixed)
        row["created_at"] = wallOf(h); row["updated_at"] = wallOf(h); row["deleted_at"] = null
        row["clocks"] = TreeMap<String, String>()
        for ((reg, value, default) in regs) {
            put(row, reg, value, h)
            if (value != default) clocks(row)[reg] = h
        }
        for ((col, obj) in objs) {
            val m = TreeMap<String, Any?>()
            for ((k, v) in obj) if (!isEmpty(v)) {
                m[k] = deepCopy(v); clocks(row)["$col.$k"] = h
            }
            row[col] = m
        }
        t.getValue(table)[key] = row
    }

    @Suppress("UNCHECKED_CAST")
    private fun setRegisters(table: String, key: String, set: Map<String, Any?>, h: String, objs: Set<String>) {
        need(table, key)
        val s = LinkedHashMap(set)
        if ("source_sentence" in s) {
            val src = listOf(s.remove("source_sentence"), s.remove("source_sentence_id"), s.remove("source_media_id"))
            regWrite(table, key, "source_sentence", src, h)
        }
        for ((name, value) in s) {
            if (name in objs) (value as Map<String, Any?>).forEach { (k, v) -> regWrite(table, key, "$name.$k", v, h) }
            else regWrite(table, key, name, value, h)
        }
    }

    /** `note.merged` / `card.merged` (8.2). */
    private fun merge(table: String, loserId: String, survivorId: String, h: String) {
        val loser = need(table, loserId)
        var survivor = need(table, survivorId)
        if (loser["merged_into_id"] != null) return
        (survivor["merged_into_id"] as String?)?.let { survivor = need(table, it) }
        for ((reg, clock) in TreeMap(clocks(loser))) {
            if (reg !in notForwarded(table)) write(survivor, reg, get(loser, reg), clock)
        }
        if (loser["deleted_at"] == null) loser["deleted_at"] = wallOf(h)
        loser["merged_into_id"] = survivor["id"]
        loser["updated_at"] = maxOf(loser["updated_at"] as Long, wallOf(h))
        for (row in t.getValue(table).values) if (row["merged_into_id"] == loserId) row["merged_into_id"] = survivor["id"]
    }

    /** Applies one op (an op envelope as JSON). Throws [MissingRow] when a referenced row is missing. */
    @Suppress("UNCHECKED_CAST")
    fun apply(op: JsonNode) {
        val type = op["op_type"].asText()
        val h = op["hlc"].asText()
        val p = plain(op["payload"]) as Map<String, Any?>
        fun s(k: String) = p[k] as String
        when (type) {
            "note.created" -> {
                val nt = need("note_type", s("note_type_id"))
                val src = listOf(p["source_sentence"] ?: "", p["source_sentence_id"], p["source_media_id"])
                create(
                    "note", s("id"),
                    mapOf(
                        "id" to s("id"), "note_type_id" to s("note_type_id"), "type" to nt["base_type"], "language" to (p["language"] ?: "ja"),
                        "kanji_literal" to p["kanji_literal"], "grammar_point_id" to p["grammar_point_id"], "origin" to (p["origin"] ?: "manual"),
                        "created_by_device_id" to op["device_id"].asText(), "merged_into_id" to null,
                    ),
                    listOf(
                        Triple("entry_seq", p["entry_seq"], null), Triple("tags", p["tags"] ?: emptyList<Any>(), emptyList<Any>()),
                        Triple("mnemonic", p["mnemonic"] ?: "", ""), Triple("source_sentence", src, listOf("", null, null)),
                    ),
                    mapOf("fields" to (p["fields"] as Map<String, Any?>? ?: emptyMap())), h,
                )
            }
            "note.updated" -> setRegisters("note", s("id"), p["set"] as Map<String, Any?>, h, setOf("fields"))
            "note.deleted" -> regWrite("note", s("id"), "deleted", null, h)
            "note.merged" -> merge("note", s("loser_id"), s("survivor_id"), h)
            "card.created" -> {
                need("note", s("note_id"))
                val preset = p["preset_id"] ?: Reserved.DEFAULT_PRESET_ID
                need("fsrs_params", preset as String)
                create(
                    "card", s("id"),
                    mapOf("id" to s("id"), "note_id" to s("note_id"), "kind" to s("kind"), "merged_into_id" to null),
                    listOf(
                        Triple("preset_id", preset, Reserved.DEFAULT_PRESET_ID), Triple("suspended", p["suspended"] ?: false, false),
                        Triple("buried_until", p["buried_until"], null), Triple("flags", p["flags"] ?: 0L, 0L),
                    ),
                    emptyMap(), h,
                )
            }
            "card.suspended" -> regWrite("card", s("id"), "suspended", true, h)
            "card.unsuspended" -> regWrite("card", s("id"), "suspended", false, h)
            "card.buried" -> regWrite("card", s("id"), "buried_until", p["until"], h)
            "card.unburied" -> regWrite("card", s("id"), "buried_until", null, h)
            "card.flag_set" -> regWrite("card", s("id"), "flags", p["flags"], h)
            "card.deleted" -> regWrite("card", s("id"), "deleted", null, h)
            "card.merged" -> merge("card", s("loser_id"), s("survivor_id"), h)
            "review.logged" -> {
                need("card", s("card_id"))
                if (s("id") in t.getValue("review_log")) return
                t.getValue("review_log")[s("id")] = linkedMapOf(
                    "id" to s("id"), "card_id" to s("card_id"), "device_id" to op["device_id"].asText(), "hlc" to (p["replay_hlc"] ?: h),
                    "reviewed_at" to p["reviewed_at"], "utc_offset_min" to p["utc_offset_min"], "review_type" to p["review_type"],
                    "rating" to p["rating"], "button" to p["button"], "duration_ms" to p["duration_ms"], "manual_action" to p["manual_action"],
                    "manual_due_at" to p["manual_due_at"], "sentence_id" to p["sentence_id"], "import_source" to p["import_source"],
                    "undone_at" to null, "undone_hlc" to null,
                )
            }
            "review.undone" -> {
                val row = need("review_log", s("review_id"))
                val cur = row["undone_hlc"] as String?
                if (cur == null || h < cur) {
                    row["undone_hlc"] = h; row["undone_at"] = wallOf(h)
                }
            }
            // Presets: existence only (the projection's caches assume the Default preset; tests with
            // other presets compare caches themselves).
            "fsrs_params.created" -> t.getValue("fsrs_params").getOrPut(s("id")) { mutableMapOf("id" to s("id"), "deleted_at" to null) }
            "fsrs_params.updated" -> need("fsrs_params", s("id"))
            "fsrs_params.deleted" -> need("fsrs_params", s("id")).let { if (it["deleted_at"] == null) it["deleted_at"] = wallOf(h) }
            "card.preset_set" -> {
                need("fsrs_params", s("preset_id"))
                regWrite("card", s("id"), "preset_id", s("preset_id"), h)
            }
            else -> error("RefReplica does not implement $type")
        }
    }

    /** Ids of card [cardId]'s replay set in replay order (9.1). */
    fun replaySet(cardId: String): List<String> = replayRows(cardId).map { it.id }

    /** `reviewed_at` of card [cardId]'s replay set in replay order. */
    fun replayTimes(cardId: String): List<Long> = replayRows(cardId).map { it.reviewedAt }

    /** Card [cardId]'s replay set as engine input, in replay order (9.1). */
    fun replayRows(cardId: String): List<ReplayReview> {
        val cards = t.getValue("card")
        return t.getValue("review_log").values
            .filter { r -> r["card_id"] == cardId || cards[r["card_id"]]?.get("merged_into_id") == cardId }
            .map(::toReplay)
            .filter { it.undoneHlc == null && it.reviewType.feedsSchedule }
            .sortedWith { a, b -> ENGINE.compare(a.key, b.key) }
    }

    private fun toReplay(r: Map<String, Any?>) = ReplayReview(
        id = r["id"] as String, cardId = r["card_id"] as String, hlc = r["hlc"] as String, reviewedAt = r["reviewed_at"] as Long,
        utcOffsetMin = (r["utc_offset_min"] as Long?)?.toInt(),
        reviewType = ReviewType.entries.first { it.wire == r["review_type"] },
        rating = (r["rating"] as Long?)?.let { v -> Rating.entries.first { it.value.toLong() == v } },
        manualAction = (r["manual_action"] as String?)?.let { a -> ManualAction.entries.first { it.wire == a } },
        manualDueAt = r["manual_due_at"] as Long?, undoneHlc = r["undone_hlc"] as String?,
    )

    /**
     * SYNC.md 11: every row of note, card, review_log (all op-written columns, tombstones and clocks);
     * merge losers only on id, merged_into_id, deleted_at; the card cache of live non-loser cards
     * (`cache` key), computed by full replay with the Default preset.
     */
    fun projection(): Map<String, Map<String, Any?>> {
        val out = TreeMap<String, Map<String, Any?>>()
        for (table in listOf("note", "card", "review_log")) {
            val rows = TreeMap<String, Any?>()
            for ((k, row) in t.getValue(table)) {
                val r = TreeMap<String, Any?>()
                if (row["merged_into_id"] != null) {
                    listOf("id", "merged_into_id", "deleted_at").forEach { r[it] = row[it] }
                } else {
                    COLUMNS.getValue(table).forEach { r[it] = canon(row[it]) }
                    if (table == "card" && row["deleted_at"] == null) r["cache"] = cacheOf(k)
                }
                rows[k] = r
            }
            out[table] = rows
        }
        return out
    }

    private fun cacheOf(cardId: String): Any? {
        val c = ENGINE.replay(replayRows(cardId), DEFAULT_PARAMS)
        return cacheMap(c.schedule, c.lastReviewId, c.weightsVersion)
    }

    companion object {
        val ENGINE: ReplayEngine = DefaultReplayEngine
        val DEFAULT_PARAMS = FsrsScheduler.paramsOf(
            PresetRow(Reserved.DEFAULT_PRESET_ID, 0.9, 36500, listOf(60, 600), listOf(600), true, List(7) { 1.0 }, null, null, 0, false),
        )
        val TABLES = listOf("note_type", "note", "card", "review_log", "fsrs_params")
        val BUILTIN_NOTE_TYPES = mapOf(
            Reserved.NOTE_TYPE_VOCAB to "vocab", Reserved.NOTE_TYPE_KANJI to "kanji", Reserved.NOTE_TYPE_PHRASE to "phrase",
            Reserved.NOTE_TYPE_GRAMMAR to "grammar", Reserved.NOTE_TYPE_SENTENCE to "sentence", Reserved.NOTE_TYPE_CUSTOM to "custom",
        )
        val COLUMNS = mapOf(
            "note" to listOf(
                "id", "note_type_id", "type", "language", "entry_seq", "kanji_literal", "grammar_point_id", "fields", "tags", "mnemonic",
                "source_sentence", "source_sentence_id", "source_media_id", "origin", "created_by_device_id", "created_at", "updated_at",
                "deleted_at", "merged_into_id", "clocks",
            ),
            "card" to listOf(
                "id", "note_id", "kind", "preset_id", "suspended", "buried_until", "flags", "created_at", "updated_at", "deleted_at",
                "merged_into_id", "clocks",
            ),
            "review_log" to listOf(
                "id", "card_id", "device_id", "hlc", "reviewed_at", "utc_offset_min", "review_type", "rating", "button", "duration_ms",
                "manual_action", "manual_due_at", "sentence_id", "import_source", "undone_at", "undone_hlc",
            ),
        )

        fun cacheMap(s: CardSchedule, lastReviewId: String?, weightsVersion: Int): Map<String, Any?> = sortedMapOf(
            "state" to s.state.wire, "step" to s.step?.toLong(), "stability" to s.stability, "difficulty" to s.difficulty,
            "due_at" to s.dueAt, "interval_days" to s.intervalDays?.toLong(), "last_review_at" to s.lastReviewAt,
            "last_review_id" to lastReviewId, "reps" to s.reps.toLong(), "lapses" to s.lapses.toLong(), "weights_version" to weightsVersion.toLong(),
        )

        fun isEmpty(v: Any?) = v == null || v == "" || (v is List<*> && v.isEmpty()) || (v is Map<*, *> && v.isEmpty())

        /** Sorted maps, lists, numbers as Long when integral: a representation `==` can compare. */
        fun canon(v: Any?): Any? = when (v) {
            is Map<*, *> -> TreeMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k as String] = canon(x) } }
            is List<*> -> v.map(::canon)
            is Int -> v.toLong()
            is Short -> v.toLong()
            else -> v
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> deepCopy(v: T): T = when (v) {
            is Map<*, *> -> (if (v is TreeMap<*, *>) TreeMap<String, Any?>() else LinkedHashMap<String, Any?>()).also { m ->
                v.forEach { (k, x) -> m[k as String] = deepCopy(x) }
            } as T
            is List<*> -> v.map { deepCopy(it) } as T
            else -> v
        }
    }
}
