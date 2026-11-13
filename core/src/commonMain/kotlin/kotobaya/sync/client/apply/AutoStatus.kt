package kotobaya.sync.client.apply

import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotobaya.contracts.queue.WordStatusRules
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.Op
import kotobaya.queue.DefaultWordStatusRules

/**
 * `word_status.auto_status` re-derivation (OPS.md: every replica re-derives it for the entry after any op that
 * changes a vocab note or card; the rule is [WordStatusRules]). Device twin of the server's `AutoStatusHook`.
 *
 * A row is created only when there is something to store: with no row, no override and a derived `new` (or none)
 * the entry stays "no row = new" (SCHEMA.md). `status` is always `coalesce(override_status, auto_status, 'new')`.
 */
internal object AutoStatus {
    private val rules: WordStatusRules = DefaultWordStatusRules
    private val ID_KEYS = listOf("id", "note_id", "card_id", "loser_id", "survivor_id", "review_id")

    /** Entries of the vocab notes [op] names (directly, or through a card or review it names), as the tables stand now. */
    fun entriesOf(tables: LearningTables, op: Op): Set<Long> {
        val out = LinkedHashSet<Long>()
        if (op.opType.wire.startsWith("fsrs_params.")) return allEntries(tables)
        if (!(op.entityType == "note" || op.entityType == "card" || op.entityType == "review_log")) return out
        for (k in ID_KEYS) {
            val id = (op.payload.members[k] as? JsonString)?.value ?: continue
            for (noteId in noteIdsOf(tables, id)) entryOf(tables, noteId)?.let(out::add)
        }
        return out
    }

    /** Every entry that has a vocab note (startup replay, preset changes). */
    fun allEntries(tables: LearningTables): Set<Long> =
        tables.all(LearningTable.NOTE).mapNotNullTo(LinkedHashSet()) { n -> entryOfRow(tables, n) } +
            tables.all(LearningTable.WORD_STATUS).mapNotNull { (it["entry_seq"] as? JsonNumber)?.value?.toLong() }

    private fun noteIdsOf(t: LearningTables, id: String): List<String> {
        val key = listOf<JsonValue>(JsonString(id))
        if (t.get(LearningTable.NOTE, key) != null) return listOf(id)
        val card = t.get(LearningTable.CARD, key) ?: t.get(LearningTable.REVIEW_LOG, key)?.get("card_id")
            ?.let { t.get(LearningTable.CARD, listOf(it)) }
        return listOfNotNull((card?.get("note_id") as? JsonString)?.value)
    }

    private fun entryOf(t: LearningTables, noteId: String): Long? =
        t.get(LearningTable.NOTE, listOf(JsonString(noteId)))?.let { entryOfRow(t, it) }

    private fun entryOfRow(t: LearningTables, note: Row): Long? {
        val seq = (note["entry_seq"] as? JsonNumber)?.value?.toLong() ?: return null
        val type = t.get(LearningTable.NOTE_TYPE, listOf(note["note_type_id"] ?: JsonNull)) ?: return null
        return seq.takeIf { (type["base_type"] as? JsonString)?.value == "vocab" }
    }

    private fun live(r: Row): Boolean = (r["deleted_at"] ?: JsonNull) == JsonNull && (r["merged_into_id"] ?: JsonNull) == JsonNull

    /** The derived status of [entry] now, or null when no live vocab note holds it. */
    fun derive(t: LearningTables, entry: Long): WordStatus? {
        val notes = t.where(LearningTable.NOTE, "entry_seq", JsonNumber(entry.toDouble()))
            .filter { live(it) && entryOfRow(t, it) == entry }
            .sortedBy { (it["id"] as? JsonString)?.value }
        val note = notes.firstOrNull() ?: return rules.autoStatus(false, emptyList())
        val cards = t.where(LearningTable.CARD, "note_id", note["id"] ?: JsonNull).filter(::live).map {
            WordCardState(
                state = CardState.entries.first { s -> s.wire == (it["state"] as? JsonString)?.value },
                intervalDays = (it["interval_days"] as? JsonNumber)?.value?.toInt(),
            )
        }
        return rules.autoStatus(true, cards)
    }

    /** Re-derives [entries] and writes `auto_status` / `status`; [nowMs] stamps `updated_at` when the status changes. */
    fun rederive(t: LearningTables, entries: Collection<Long>, nowMs: Long?) {
        for (entry in entries) {
            val auto = derive(t, entry)
            val key = listOf<JsonValue>(JsonNumber(entry.toDouble()))
            val existing = t.get(LearningTable.WORD_STATUS, key)
            if (existing == null && (auto == null || auto == WordStatus.NEW)) continue
            val row = LinkedHashMap(
                existing ?: linkedMapOf(
                    "entry_seq" to key[0], "status" to JsonString("new"), "auto_status" to JsonNull,
                    "override_status" to JsonNull, "override_hlc" to JsonNull, "override_origin" to JsonNull,
                    "updated_at" to instant(nowMs ?: 0L),
                ),
            )
            val override = (row["override_status"] as? JsonString)?.value
            val status = override ?: auto?.wire ?: "new"
            val newAuto: JsonValue = auto?.let { JsonString(it.wire) } ?: JsonNull
            if (existing != null && override == null && (row["override_hlc"] ?: JsonNull) == JsonNull && status == "new") {
                // never overridden and nothing to show: back to "no row = new", so replicas agree whatever the order
                t.delete(LearningTable.WORD_STATUS, key)
                continue
            }
            if (existing != null && row["auto_status"] == newAuto && row["status"] == JsonString(status)) continue
            row["auto_status"] = newAuto
            row["status"] = JsonString(status)
            if (existing != null && nowMs != null) row["updated_at"] = instant(nowMs)
            t.put(LearningTable.WORD_STATUS, row)
        }
    }
}
