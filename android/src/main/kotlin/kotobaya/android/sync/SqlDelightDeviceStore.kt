package kotobaya.android.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotobaya.contracts.Uuid
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.store.DeviceStore
import kotobaya.sync.client.store.OutboxTable
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.StoreTransaction
import kotobaya.sync.client.store.SyncStateTable

/**
 * [DeviceStore] over the app database (SYNC.md 4, 5.4, 6.1; `Local.sq`, `Learning.sq`, `Media.sq`).
 *
 * - One SQLite transaction per outermost [transaction]; a nested call joins it (the T303a contract:
 *   the inner block commits or rolls back with the outer one, even if the outer block catches the
 *   inner exception — SQLDelight's own nesting would roll the outer back in that case).
 * - If the block throws, SQLite rolls back everything it wrote (outbox, `sync_state`, learning
 *   tables) and the exception propagates.
 * - Durability: the driver must be opened with WAL and `synchronous=FULL` ([DurableDriver]), so a
 *   committed mint or push result survives a process kill and a power cut.
 *
 * `sync_state` uses the generated `SyncOps.sq` / `MetaLimits.sq` queries; `pending_op` and the
 * learning tables use statements on [driver] (the contract `.sq` files hold only DDL, so there are
 * no generated queries for them).
 *
 * Thread use: a transaction belongs to the thread that opened it; the block never suspends.
 * [onCommit] is called after each outermost commit (the status repository refreshes on it).
 */
class SqlDelightDeviceStore(
    private val driver: SqlDriver,
    private val db: AppDatabase = AppDatabase(driver),
) : DeviceStore<SqlLearningTables> {
    private val schema: LearningSchema by lazy { LearningSchema.read(driver) }
    private val active = ThreadLocal<Tx?>()
    private val commitListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Registers [listener], called (on the committing thread) after every outermost commit. */
    fun onCommit(listener: () -> Unit) {
        commitListeners += listener
    }

    override fun <R> transaction(block: (StoreTransaction<SqlLearningTables>) -> R): R = run(true, block)

    /** A [transaction] that does not call the [onCommit] listeners (for readers such as the status). */
    fun <R> read(block: (StoreTransaction<SqlLearningTables>) -> R): R = run(false, block)

    private fun <R> run(notify: Boolean, block: (StoreTransaction<SqlLearningTables>) -> R): R {
        active.get()?.let { return block(it) }
        val tx = Tx()
        // re-assert durability on the connection about to write (cannot change inside a transaction)
        driver.execute(null, "PRAGMA synchronous = FULL", 0)
        val result = try {
            active.set(tx)
            db.transactionWithResult { block(tx) }
        } finally {
            active.set(null)
        }
        if (notify) for (l in commitListeners) runCatching { l() }
        return result
    }

    private inner class Tx : StoreTransaction<SqlLearningTables> {
        override val learning: SqlLearningTables = SqlLearningTables(driver, schema)
        override val outbox: OutboxTable = SqlOutbox(driver)
        override val syncState: SyncStateTable = object : SyncStateTable {
            override fun get(key: String): String? = db.syncOpsQueries.getSyncState(key).executeAsOneOrNull()

            override fun put(key: String, value: String) = db.syncOpsQueries.putSyncState(key, value)

            override fun remove(key: String) = db.metaLimitsQueries.deleteSyncState(key)
        }
    }
}

/** `pending_op` (Local.sq) as T303a's [OutboxTable]. */
internal class SqlOutbox(private val driver: SqlDriver) : OutboxTable {
    override fun insert(row: PendingOpRow) {
        check(get(row.opId) == null) { "pending_op ${row.opId} already exists" }
        driver.execute(
            null,
            "INSERT INTO pending_op (op_id, op_type, op_version, entity_type, entity_id, hlc, payload, created_at, " +
                "state, attempts, last_error, acked_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            12,
        ) {
            bindString(0, row.opId)
            bindString(1, row.opType)
            bindLong(2, row.opVersion.toLong())
            bindString(3, row.entityType)
            bindString(4, row.entityId)
            bindString(5, row.hlc)
            bindString(6, row.payload)
            bindLong(7, row.createdAt)
            bindString(8, row.state.wire)
            bindLong(9, row.attempts.toLong())
            bindString(10, row.lastError)
            bindLong(11, row.ackedSeq)
        }
    }

    override fun get(opId: Uuid): PendingOpRow? =
        rows("WHERE op_id = ?", 1) { it.bindString(0, opId) }.firstOrNull()

    override fun inState(state: PendingState, limit: Int): List<PendingOpRow> =
        rows("WHERE state = ? ORDER BY hlc LIMIT ?", 2) {
            it.bindString(0, state.wire)
            it.bindLong(1, limit.toLong())
        }

    override fun markAcked(opId: Uuid, seq: Long) {
        update("state = 'acked', acked_seq = ?", opId) { bindLong(0, seq) }
    }

    override fun markRejected(opId: Uuid, lastError: String) {
        update("state = 'rejected', last_error = ?, acked_seq = NULL", opId) { bindString(0, lastError) }
    }

    override fun markPending(opId: Uuid, attempts: Int) {
        update("state = 'pending', acked_seq = NULL, attempts = ?", opId) { bindLong(0, attempts.toLong()) }
    }

    override fun setAttempts(opId: Uuid, attempts: Int) {
        update("attempts = ?", opId) { bindLong(0, attempts.toLong()) }
    }

    override fun incrementAttempts(opIds: Collection<Uuid>) {
        for (id in opIds) {
            driver.execute(null, "UPDATE pending_op SET attempts = attempts + 1 WHERE op_id = ?", 1) { bindString(0, id) }
        }
    }

    override fun delete(opId: Uuid) {
        driver.execute(null, "DELETE FROM pending_op WHERE op_id = ?", 1) { bindString(0, opId) }
    }

    override fun deleteAckedThrough(maxSeq: Long): Int =
        driver.execute(null, "DELETE FROM pending_op WHERE state = 'acked' AND acked_seq <= ?", 1) {
            bindLong(0, maxSeq)
        }.value.toInt()

    override fun count(state: PendingState): Int = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM pending_op WHERE state = ?",
        { c -> c.next(); QueryResult.Value(c.getLong(0)?.toInt() ?: 0) },
        1,
    ) { bindString(0, state.wire) }.value

    private fun update(set: String, opId: Uuid, binders: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit) {
        val n = set.count { it == '?' }
        driver.execute(null, "UPDATE pending_op SET $set WHERE op_id = ?", n + 1) {
            binders()
            bindString(n, opId)
        }
    }

    private fun rows(tail: String, params: Int, binders: (app.cash.sqldelight.db.SqlPreparedStatement) -> Unit): List<PendingOpRow> =
        driver.executeQuery(
            null,
            "SELECT op_id, op_type, op_version, entity_type, entity_id, hlc, payload, created_at, state, attempts, " +
                "last_error, acked_seq FROM pending_op $tail",
            { c ->
                val out = ArrayList<PendingOpRow>()
                while (c.next().value) out += read(c)
                QueryResult.Value(out)
            },
            params,
        ) { binders(this) }.value

    private fun read(c: SqlCursor): PendingOpRow {
        val stateText = c.str(8)
        return PendingOpRow(
            opId = c.str(0),
            opType = c.str(1),
            opVersion = c.long(2).toInt(),
            entityType = c.str(3),
            entityId = c.str(4),
            hlc = c.str(5),
            payload = c.str(6),
            createdAt = c.long(7),
            state = PendingState.entries.firstOrNull { it.wire == stateText }
                ?: throw IllegalStateException("pending_op state '$stateText'"),
            attempts = c.long(9).toInt(),
            lastError = c.getString(10),
            ackedSeq = c.getLong(11),
        )
    }
}

internal fun SqlCursor.str(i: Int): String = checkNotNull(getString(i)) { "column $i is NULL" }

internal fun SqlCursor.long(i: Int): Long = checkNotNull(getLong(i)) { "column $i is NULL" }
