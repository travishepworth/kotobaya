package kotobaya.replay

import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayEngine
import kotobaya.contracts.replay.ReplayReview

/**
 * One card's replay set held in memory with a checkpoint (the schedule after each row), so an
 * out-of-order insert or an undo recomputes only from its **insertion point** instead of from the
 * start (T203 "incremental replay from the insertion point"). For callers that apply many ops to one
 * card in one transaction (a pull page, a rebuild, the acceptance simulator).
 *
 * Guarantee: after any sequence of [insert], [undo] and [setParams], [cache] equals
 * `engine.replay(rows, params)` of every row passed to [insert] (minus undone ones) with the current
 * params — bit-identical, because every step goes through [ReplayEngine.fold].
 *
 * **Refusal (SYNC.md 9.4, C-8).** If that replay would throw (a row violates the `review_log` CHECKs),
 * no method throws: the row stays in [replaySet], [cache] is the refused cache
 * `CardCache(CardSchedule.NEW, Reserved.REPLAY_REFUSED_ID, params.weightsVersion)` and [refusal] holds
 * the exception for the caller to log at ERROR. The state stays consistent ([replaySet] and the
 * checkpoints always cover the same rows), so undoing the offending row or new params recompute
 * normally and the card is current again.
 *
 * Not thread-safe; deterministic. Memory: one [CardSchedule] per row in the set.
 *
 * Example: rows r1@10, r2@30 inserted (both fold at the end); then r3@20 is inserted → position 1,
 * steps recomputed for r3 and r2 only, starting from the checkpoint after r1.
 */
public class IncrementalReplay(
    private val engine: ReplayEngine,
    params: SchedulerParams,
) {
    /** Current effective preset parameters. */
    public var params: SchedulerParams = params
        private set

    private val rows = ArrayList<ReplayReview>()
    private val after = ArrayList<CardSchedule>()
    private val ids = HashSet<Uuid>()

    /** Index of the first row whose fold was refused; checkpoints from it on are not valid. -1 = none. */
    private var refusedAt = -1

    /**
     * The exception of the refused replay (SYNC.md 9.4), or null when [cache] is a normal cache. The
     * caller logs it with the card id at ERROR and writes [cache] (the refused cache) as usual.
     */
    public var refusal: IllegalArgumentException? = null
        private set

    /** Rows of the replay set in SYNC.md 9.1 order. */
    public val replaySet: List<ReplayReview> get() = rows.toList()

    /** The card cache: equal to `engine.replay(replaySet, params)`. */
    public val cache: CardCache
        get() = if (refusal != null) {
            CardCache(CardSchedule.NEW, Reserved.REPLAY_REFUSED_ID, params.weightsVersion)
        } else {
            CardCache(after.lastOrNull() ?: CardSchedule.NEW, rows.lastOrNull()?.id, params.weightsVersion)
        }

    /**
     * Adds a `review_log` row. Returns the index it was placed at, or -1 if nothing changed: the row is
     * drill/cram or undone (not in a replay set), or a row with this id was already inserted
     * (SYNC.md 7.9 rule 1: the first insert wins, even with different content).
     */
    public fun insert(review: ReplayReview): Int {
        if (review.id in ids) return -1
        if (!(review.reviewType.feedsSchedule && review.undoneHlc == null)) {
            // a non-feeding row still claims its id, so a later duplicate with a feeding type is ignored
            ids.add(review.id)
            return -1
        }
        ids.add(review.id)
        val pos = insertionPoint(review)
        rows.add(pos, review)
        after.add(pos, CardSchedule.NEW) // placeholder, recomputed below
        recomputeFrom(pos)
        return pos
    }

    /**
     * Removes the row with [id] from the replay set (its `review.undone` was applied and changed it
     * from not undone to undone). Returns false if no such row is in the set (unknown id, drill/cram,
     * or already undone).
     */
    public fun undo(id: Uuid): Boolean {
        val pos = rows.indexOfFirst { it.id == id }
        if (pos < 0) return false
        rows.removeAt(pos)
        after.removeAt(pos)
        recomputeFrom(pos)
        return true
    }

    /** New effective preset parameters: full recompute (SYNC.md 9.2 preset rows). */
    public fun setParams(params: SchedulerParams) {
        this.params = params
        recomputeFrom(0)
    }

    private fun insertionPoint(review: ReplayReview): Int {
        var lo = 0
        var hi = rows.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (engine.compare(rows[mid].key, review.key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Recomputes the checkpoints from [pos] (or from the first refused row, if earlier: checkpoints
     * after it were never computed). A refused fold stops the walk and records [refusal]; the rest of
     * the checkpoints keep placeholders until a later recompute reaches them.
     */
    private fun recomputeFrom(pos: Int) {
        val start = if (refusedAt in 0 until pos) refusedAt else pos
        refusedAt = -1
        refusal = null
        var c = if (start == 0) {
            CardCache(CardSchedule.NEW, null, params.weightsVersion)
        } else {
            CardCache(after[start - 1], rows[start - 1].id, params.weightsVersion)
        }
        for (i in start until rows.size) {
            c = try {
                engine.fold(c, rows[i], params)
            } catch (e: IllegalArgumentException) {
                refusedAt = i
                refusal = e
                return
            }
            after[i] = c.schedule
        }
    }
}
