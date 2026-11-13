package kotobaya.sync.client.rejected

import kotobaya.contracts.HlcString
import kotobaya.contracts.sync.HlcRules
import kotobaya.sync.client.remint.ClockActionBlock
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncBookkeeping
import kotobaya.sync.client.store.bookkeeping

/** Clock repair and the clock preconditions of SYNC.md 10.4. */
internal object ClockRepair {
    /** `|server_time − pt|` at most this for the clock to count as correct: 5 minutes. */
    const val CORRECT_CLOCK_MS: Long = SyncBookkeeping.CLOCK_WARN_MS

    /**
     * The preconditions shared by Re-mint and Discard of `clock_ahead` rows: no `pending` row, and
     * the last response showed `|server_time − pt| <= 5 min`. Null when they hold.
     */
    fun preconditionsBlock(tx: StoreTransaction<*>): ClockActionBlock? {
        if (tx.outbox.count(PendingState.PENDING) > 0) return ClockActionBlock.PENDING_OPS
        val skew = tx.bookkeeping.serverSkewMs ?: return ClockActionBlock.CLOCK_NOT_CONFIRMED
        if ((if (skew < 0) -skew else skew) > CORRECT_CLOCK_MS) return ClockActionBlock.CLOCK_NOT_CONFIRMED
        return null
    }

    /** `hlc_last` after the repair: the greatest of `hlc_acked` and the hlc of every [kept] row (null = none). */
    fun repairedHlcLast(bk: SyncBookkeeping, kept: List<PendingOpRow>, rules: HlcRules): HlcString? {
        var best: HlcString? = bk.hlcAcked
        for (r in kept) if (best == null || rules.compare(r.hlc, best) > 0) best = r.hlc
        return best
    }

    /** Sets `hlc_last` as [repairedHlcLast] over every row still in the outbox (call after deleting the discarded rows). */
    fun repair(tx: StoreTransaction<*>, rules: HlcRules) {
        val kept = PendingState.entries.flatMap { tx.outbox.inState(it) }
        tx.bookkeeping.hlcLast = repairedHlcLast(tx.bookkeeping, kept, rules)
    }
}
