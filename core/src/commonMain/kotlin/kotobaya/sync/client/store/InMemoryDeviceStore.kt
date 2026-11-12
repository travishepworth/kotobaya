package kotobaya.sync.client.store

import kotobaya.contracts.Uuid

/**
 * [DeviceStore] held in memory, for tests and the JVM simulation (T313). Each transaction works on
 * a copy of the committed state (the learning tables copied with [copyLearning]) and replaces it
 * only when the block returns, so a block that throws leaves nothing behind, exactly like a SQLite
 * rollback. A nested [transaction] joins the outer one. Not thread-safe: one caller at a time
 * (common code; the JS target is single-threaded).
 */
public class InMemoryDeviceStore<L>(
    initialLearning: L,
    private val copyLearning: (L) -> L,
) : DeviceStore<L> {
    private var committed: State<L> = State(LinkedHashMap(), LinkedHashMap(), initialLearning)
    private var active: Tx<L>? = null

    override fun <R> transaction(block: (StoreTransaction<L>) -> R): R {
        active?.let { return block(it) }
        val tx = Tx(committed.copy(copyLearning))
        active = tx
        try {
            val result = block(tx)
            committed = tx.state
            return result
        } finally {
            active = null
        }
    }

    /** Committed `pending_op` rows, ascending hlc (test helper; outside any transaction). */
    public fun rows(): List<PendingOpRow> = committed.outbox.values.sortedBy { it.hlc }

    /** Committed `sync_state` (test helper). */
    public fun syncState(): Map<String, String> = LinkedHashMap(committed.syncState)

    /** A copy of the committed learning tables (test helper). */
    public fun learning(): L = copyLearning(committed.learning)

    private class State<L>(
        val outbox: LinkedHashMap<Uuid, PendingOpRow>,
        val syncState: LinkedHashMap<String, String>,
        val learning: L,
    ) {
        fun copy(copyLearning: (L) -> L): State<L> =
            State(LinkedHashMap(outbox), LinkedHashMap(syncState), copyLearning(learning))
    }

    private class Tx<L>(val state: State<L>) : StoreTransaction<L> {
        override val learning: L get() = state.learning

        override val syncState: SyncStateTable = object : SyncStateTable {
            override fun get(key: String): String? = state.syncState[key]

            override fun put(key: String, value: String) {
                state.syncState[key] = value
            }

            override fun remove(key: String) {
                state.syncState.remove(key)
            }
        }

        override val outbox: OutboxTable = object : OutboxTable {
            private val rows get() = state.outbox

            override fun insert(row: PendingOpRow) {
                check(row.opId !in rows) { "pending_op ${row.opId} already exists" }
                rows[row.opId] = row
            }

            override fun get(opId: Uuid): PendingOpRow? = rows[opId]

            override fun inState(state: PendingState, limit: Int): List<PendingOpRow> =
                rows.values.filter { it.state == state }.sortedBy { it.hlc }.take(limit)

            override fun markAcked(opId: Uuid, seq: Long) {
                update(opId) { it.copy(state = PendingState.ACKED, ackedSeq = seq) }
            }

            override fun markRejected(opId: Uuid, lastError: String) {
                update(opId) { it.copy(state = PendingState.REJECTED, lastError = lastError, ackedSeq = null) }
            }

            override fun markPending(opId: Uuid, attempts: Int) {
                update(opId) { it.copy(state = PendingState.PENDING, ackedSeq = null, attempts = attempts) }
            }

            override fun incrementAttempts(opIds: Collection<Uuid>) {
                for (id in opIds) update(id) { it.copy(attempts = it.attempts + 1) }
            }

            override fun delete(opId: Uuid) {
                rows.remove(opId)
            }

            override fun deleteAckedThrough(maxSeq: Long): Int {
                val doomed = rows.values.filter { it.state == PendingState.ACKED && (it.ackedSeq ?: Long.MAX_VALUE) <= maxSeq }
                for (r in doomed) rows.remove(r.opId)
                return doomed.size
            }

            override fun count(state: PendingState): Int = rows.values.count { it.state == state }

            private inline fun update(opId: Uuid, f: (PendingOpRow) -> PendingOpRow) {
                val row = rows[opId] ?: return
                rows[opId] = f(row)
            }
        }
    }
}
