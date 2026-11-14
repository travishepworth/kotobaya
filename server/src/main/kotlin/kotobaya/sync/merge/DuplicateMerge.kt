package kotobaya.sync.merge

import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.server.Uuid7
import kotobaya.sync.Collision
import kotobaya.sync.MergePolicy
import kotobaya.sync.OpTx
import kotobaya.sync.DefaultHlcRules
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap

/**
 * The server's duplicate detection and merge emission (SYNC.md 8.1). Install it as
 * `SyncConfig.merge`; every replica then performs the merge itself from the emitted ops (8.2).
 *
 * How it uses the T202b seam ([MergePolicy]):
 *
 * - [collision] is called by the apply of a `note.created`, a `note.updated` that won `entry_seq`
 *   on a live note, or a `card.created`, when that write would make a second live holder of a
 *   natural key. The newcomer is always the loser and the live holder the survivor (8.1: "the note
 *   that already held the reference, regardless of ids and clocks"). The merge op's HLC `h` is
 *   reserved there and `wall(h)` returned, so the loser is tombstoned in the very statement that
 *   writes it (the partial unique indexes never fire) with exactly the `deleted_at` every replica
 *   derives from the pulled merge op (8.2 step 2).
 * - [afterLogged] runs once the triggering op's `change` row exists and emits, with
 *   `caused_by_op_id` = the triggering op and consecutive `seq`:
 *   - note collision: `note.merged {N → W}` with `h`; then for each card c of N that is live and not
 *     merged, ordered by `(kind, id)` byte-wise: if W has no live card of `c.kind`, a
 *     `card.created {id: new, note_id: W, kind}` (no other properties, so no clocks); then
 *     `card.merged {c → W's live card of that kind}`.
 *   - card collision: `card.merged {new → existing}` with `h`.
 *   - "card created on a merged note" (no collision; the card was inserted live on loser N):
 *     the card is merged into W exactly as in the note case.
 *
 * Every emitted op goes through [OpTx.emit], i.e. the same apply function, validation and log as
 * any server-minted op; their own [afterLogged] calls find nothing to do (survivors are live, so
 * never merged, so a `card.created` on W never triggers anything).
 *
 * Idempotent by construction: a re-applied creation is a no-op and never collides; a `card.created`
 * whose card already existed is not live-and-unmerged on a merged note (it was merged when it was
 * first created), so nothing is emitted twice. A pushed duplicate `op_id` never reaches apply.
 *
 * [newCardId] mints the id of a survivor card the server creates (tests inject the ids a scenario
 * log shows; SYNC.md 13 allows them to differ).
 */
class DuplicateMerge(
    private val newCardId: () -> String = { Uuid7.generate().toString() },
) : MergePolicy {
    private val log = LoggerFactory.getLogger(DuplicateMerge::class.java)

    /** Collisions seen during an apply, waiting for the op's `change` row: per transaction, by op id. */
    private val pending: MutableMap<OpTx, MutableMap<String, Reserved>> = Collections.synchronizedMap(WeakHashMap())

    private class Reserved(val collision: Collision, val hlc: String)

    override fun collision(tx: OpTx, op: Op, collision: Collision): Long {
        val h = tx.reserveHlc()
        pending.getOrPut(tx) { mutableMapOf() }[op.opId] = Reserved(collision, h)
        return DefaultHlcRules.wall(h)
    }

    override fun afterLogged(tx: OpTx, op: Op, seq: Long) {
        val reserved = pending[tx]?.remove(op.opId)
        if (reserved != null) {
            val c = reserved.collision
            val loser = c.loserId.toString()
            val survivor = c.survivorId.toString()
            // OBS-1s: a duplicate-key conflict between two devices is WARN, with the op that caused it.
            log.warn(
                "op conflict: duplicate {} ({}), merging {} into {}", c.table, c.key, loser, survivor,
                kv("op_id", op.opId), kv("op_type", op.opType.wire), kv("outcome", "conflict"), kv("reason", "duplicate_${c.table}"), kv("seq", seq),
            )
            when (c.table) {
                "note" -> {
                    tx.emit(OpType.NOTE_MERGED, pair(loser, survivor), causedByOpId = op.opId, hlc = reserved.hlc)
                    for (card in liveCardsOf(tx, loser)) mergeCardInto(tx, card, survivor, op.opId)
                }
                "card" -> tx.emit(OpType.CARD_MERGED, pair(loser, survivor), causedByOpId = op.opId, hlc = reserved.hlc)
                else -> error("unexpected collision table ${c.table}")
            }
            return
        }
        if (op.opType == OpType.CARD_CREATED) cardOnMergedNote(tx, op)
    }

    /** 8.1 "Card created on a merged note": the new card lives on loser N; merge it into W as in step 2. */
    private fun cardOnMergedNote(tx: OpTx, op: Op) {
        val id = (op.payload.members["id"] as? JsonString)?.value ?: return
        val r = tx.dsl.fetchOne(
            "select c.kind, n.merged_into_id::text from app.card c join app.note n on n.id = c.note_id " +
                "where c.id = ?::uuid and c.deleted_at is null and c.merged_into_id is null",
            id,
        ) ?: return
        val survivorNote = r.get(1, String::class.java) ?: return
        mergeCardInto(tx, LiveCard(id, r.get(0, String::class.java)), survivorNote, op.opId)
    }

    private data class LiveCard(val id: String, val kind: String)

    /** Cards of note [noteId] with `deleted_at IS NULL` and `merged_into_id IS NULL`, by `(kind, id)` byte-wise. */
    private fun liveCardsOf(tx: OpTx, noteId: String): List<LiveCard> = tx.dsl.fetch(
        "select id::text, kind from app.card where note_id = ?::uuid and deleted_at is null and merged_into_id is null " +
            "order by kind collate \"C\", id::text collate \"C\"",
        noteId,
    ).map { LiveCard(it.get(0, String::class.java), it.get(1, String::class.java)) }

    /** 8.1 step 2 for one card: make sure W has a live card of its kind, then `card.merged` into it. */
    private fun mergeCardInto(tx: OpTx, card: LiveCard, survivorNote: String, cause: String) {
        val target = tx.dsl.fetchOptional(
            "select id::text from app.card where note_id = ?::uuid and kind = ? and deleted_at is null " +
                "order by id::text collate \"C\" limit 1",
            survivorNote, card.kind,
        ).map { it.get(0, String::class.java) }.orElse(null) ?: newCardId().also { id ->
            tx.emit(
                OpType.CARD_CREATED,
                JsonObject(linkedMapOf("id" to JsonString(id), "note_id" to JsonString(survivorNote), "kind" to JsonString(card.kind))),
                causedByOpId = cause,
            )
        }
        tx.emit(OpType.CARD_MERGED, pair(card.id, target), causedByOpId = cause)
    }

    private fun pair(loser: String, survivor: String) =
        JsonObject(linkedMapOf("loser_id" to JsonString(loser), "survivor_id" to JsonString(survivor)))
}
