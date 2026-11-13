package kotobaya.sync

import kotobaya.contracts.WordStatus
import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.queue.WordCardState
import kotobaya.contracts.queue.WordStatusRules
import kotobaya.queue.DefaultWordStatusRules
import org.jooq.DSLContext

/**
 * [AutoStatusHook] with the core's rule ([DefaultWordStatusRules]); the server twin of the device's
 * `AutoStatus.rederive` (same inputs: the lowest-id live vocab note of the entry and its live cards; same
 * row policy: no row is created for a derived `new`, a never-overridden row that would show `new` is deleted).
 * Runs in the op transaction. `updated_at` is local "now" when the derived status changes (SYNC.md 11).
 */
class CoreAutoStatusHook(private val rules: WordStatusRules = DefaultWordStatusRules) : AutoStatusHook {
    override fun entriesTouched(tx: DSLContext, entrySeqs: Set<Long>) {
        for (seq in entrySeqs.sorted()) rederive(tx, seq)
    }

    private fun derive(tx: DSLContext, seq: Long): WordStatus? {
        val noteId = tx.fetchOptional(
            "select n.id from app.note n join app.note_type t on t.id = n.note_type_id " +
                "where n.entry_seq = ? and t.base_type = 'vocab' and n.deleted_at is null and n.merged_into_id is null " +
                "order by n.id::text limit 1",
            seq,
        ).map { it.get(0) }.orElse(null) ?: return rules.autoStatus(false, emptyList())
        val cards = tx.fetch(
            "select state, interval_days from app.card where note_id = ? and deleted_at is null and merged_into_id is null",
            noteId,
        ).map { r ->
            WordCardState(
                state = CardState.entries.first { it.wire == r.get(0, String::class.java) },
                intervalDays = r.get(1, Number::class.java)?.toInt(),
            )
        }
        return rules.autoStatus(true, cards)
    }

    private fun rederive(tx: DSLContext, seq: Long) {
        val auto = derive(tx, seq)
        val existing = tx.fetchOptional(
            "select auto_status, override_status, override_hlc, status from app.word_status where entry_seq = ?", seq,
        ).orElse(null)
        if (existing == null) {
            if (auto == null || auto == WordStatus.NEW) return
            tx.execute(
                "insert into app.word_status (entry_seq, status, auto_status, updated_at) values (?, ?, ?, now())",
                seq, auto.wire, auto.wire,
            )
            return
        }
        val override = existing.get(1, String::class.java)
        val status = override ?: auto?.wire ?: "new"
        if (override == null && existing.get(2, String::class.java) == null && status == "new") {
            tx.execute("delete from app.word_status where entry_seq = ?", seq)
            return
        }
        if (existing.get(0, String::class.java) == auto?.wire && existing.get(3, String::class.java) == status) return
        tx.execute(
            "update app.word_status set auto_status = ?, status = ?, updated_at = now() where entry_seq = ?",
            auto?.wire, status, seq,
        )
    }
}
