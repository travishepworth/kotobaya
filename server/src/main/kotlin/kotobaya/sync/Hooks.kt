package kotobaya.sync

import kotobaya.contracts.sync.Op
import org.jooq.DSLContext
import java.util.UUID

/**
 * Card-cache extension point (SYNC.md 9.2). Called **inside the op transaction** (it holds
 * `pg_advisory_xact_lock(7033001)`), right after the op that forces the replay was applied and
 * before its `change` row is written. T202c wires the T203 replay engine here.
 *
 * The default [NoOp] is deliberate and documented: until T203 lands, the server keeps card cache
 * columns at their creation defaults (`state = 'new'`, …). Nothing is lost by that — caches are a
 * pure function of `review_log` and the preset (9.3), so the startup replay of SYNC.md 10.5 (run
 * when `core_scheduler_version` changes, i.e. when the real engine is first deployed) rebuilds
 * every cache from the log.
 *
 * Every method has a no-op default so implementations override only what they need.
 */
interface CardCacheHook {
    /**
     * A schedule-feeding `review.logged` row (`scheduled`, `learning`, `manual`) was inserted.
     * [targetCardId] is its card, or that card's survivor if merged (9.2 "target"). The hook decides
     * fold vs full replay (9.2: fold only if it sorts after `last_review_id` and `weights_version`
     * matches the effective preset's).
     */
    fun reviewLogged(tx: DSLContext, reviewId: UUID, targetCardId: UUID) {}

    /** A schedule-feeding review went from not-undone to undone (`review.undone`): full replay of [targetCardId]. */
    fun reviewUndone(tx: DSLContext, reviewId: UUID, targetCardId: UUID) {}

    /** Full replay of these cards: the survivor of a `card.merged`, or the cards a `card.preset_set` won on. */
    fun replayCards(tx: DSLContext, cardIds: Set<UUID>) {}

    /**
     * A preset changed in a way that invalidates caches: [PresetChange.UPDATED] (a register other
     * than `name` won: replay every live card whose effective preset it is), [PresetChange.DELETED]
     * (first tombstoning: every live card with that `preset_id`), [PresetChange.PUBLISHED] (new
     * weights: caches are stale by `weights_version` and may be replayed lazily, 9.3).
     */
    fun presetChanged(tx: DSLContext, presetId: UUID, change: PresetChange) {}

    companion object {
        /** Keeps caches untouched (see the class comment). */
        val NoOp: CardCacheHook = object : CardCacheHook {}
    }
}

/** Why [CardCacheHook.presetChanged] was called. */
enum class PresetChange { UPDATED, DELETED, PUBLISHED }

/**
 * `word_status.auto_status` re-derivation (OPS.md: "every replica re-derives auto_status for the
 * entry"; the rule is the core's). Called in the op transaction with the entries whose vocab notes
 * or cards an applied op may have changed. Default: no-op (`auto_status` is derived, never synced,
 * and excluded from convergence, SYNC.md 11 item 4); the owner of the core rule wires it.
 */
fun interface AutoStatusHook {
    fun entriesTouched(tx: DSLContext, entrySeqs: Set<Long>)

    companion object {
        val NoOp = AutoStatusHook { _, _ -> }
    }
}

/** A write that would make a second live holder of a natural key (SYNC.md 8.1; the unique indexes of V1). */
data class Collision(
    /** `note` or `card`. */
    val table: String,
    /** The row the op writes: the loser (always the newcomer, 8.1). */
    val loserId: UUID,
    /** The live row that already holds the key: the survivor. */
    val survivorId: UUID,
    /** The contested key, for logs: `entry_seq=1358280`, `kanji_literal=食`, `grammar_point_id=…`, `kind=meaning`. */
    val key: String,
)

/**
 * Duplicate-merge extension point (SYNC.md 8.1), implemented by T202c in `kotobaya.sync.merge`.
 *
 * - [collision] is called **during** the apply of a `note.created`, a `note.updated` that wins
 *   `entry_seq` on a live note, or a `card.created`, when the write would create a second live
 *   holder of an `entry_seq` / `kanji_literal` / `grammar_point_id` / `(note_id, kind)`. Postgres'
 *   partial unique indexes would reject that write, so the loser must be tombstoned **in the same
 *   statement** (V1 `smoke.sql`): the policy returns the `deleted_at` (epoch ms) the apply then
 *   writes together with the op's values — normally `wall` of the merge op's HLC, which the policy
 *   mints now (`tx.clock.mint()`) and keeps for [afterLogged]. `merged_into_id` is set later by
 *   applying `note.merged` / `card.merged` (8.2 step 2 leaves an existing `deleted_at` alone).
 * - [afterLogged] runs after every applied op's `change` row is written (pushed or server-minted),
 *   in the same transaction; a policy emits `note.merged`, `card.created`, `card.merged` there with
 *   `tx.emit(…, causedByOpId = op.opId)`, which applies and logs them with the next seqs. It also
 *   handles the non-colliding case "card created on a merged note".
 *
 * The default [Unavailable] refuses collisions with [MergeUnavailableException] (the push answers
 * 503 and the client retries later; nothing is applied or lost) and does nothing after logging.
 */
interface MergePolicy {
    fun collision(tx: OpTx, op: Op, collision: Collision): Long

    fun afterLogged(tx: OpTx, op: Op, seq: Long) {}

    companion object {
        val Unavailable: MergePolicy = object : MergePolicy {
            override fun collision(tx: OpTx, op: Op, collision: Collision): Long =
                throw MergeUnavailableException(collision)
        }
    }
}

/** No [MergePolicy] is installed and an op would create a duplicate live row. The whole transaction rolls back. */
class MergeUnavailableException(val collision: Collision) :
    IllegalStateException("duplicate ${collision.table} (${collision.key}) needs the merge procedure, which is not installed")

/** One op that a transaction applied and logged. */
data class LoggedOp(val op: Op, val seq: Long)

/**
 * Called once per committed-to-be op transaction that logged at least one op, inside it (so a
 * `NOTIFY` is delivered only on commit). Wiring passes `EventBus.syncChangedInTx` (event
 * `sync.changed`) and may publish `note.created` from [ops].
 */
fun interface SyncEvents {
    fun logged(tx: DSLContext, highWaterSeq: Long, ops: List<LoggedOp>)

    companion object {
        val NoOp = SyncEvents { _, _, _ -> }
    }
}
