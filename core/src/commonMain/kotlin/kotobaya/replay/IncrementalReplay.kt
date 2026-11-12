package kotobaya.replay

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

    /** Rows of the replay set in SYNC.md 9.1 order. */
    public val replaySet: List<ReplayReview> get() = rows.toList()

    /** The card cache: equal to `engine.replay(replaySet, params)`. */
    public val cache: CardCache
        get() = CardCache(after.lastOrNull() ?: CardSchedule.NEW, rows.lastOrNull()?.id, params.weightsVersion)

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

    private fun recomputeFrom(pos: Int) {
        var c = if (pos == 0) {
            CardCache(CardSchedule.NEW, null, params.weightsVersion)
        } else {
            CardCache(after[pos - 1], rows[pos - 1].id, params.weightsVersion)
        }
        for (i in pos until rows.size) {
            c = engine.fold(c, rows[i], params)
            after[i] = c.schedule
        }
    }
}
