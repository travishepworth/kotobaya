package kotobaya.server

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNC.md 6.3 and 10.5 startup ordering, lock 7033001, restore finalization. */
class StartupSequenceTest {
    private fun Database.state(key: String): String? =
        txBlocking { it.fetchOptional("select value from app.server_state where key = ?", key) }
            .map { r -> r.get(0, String::class.java) }.orElse(null)

    @Test
    fun freshDatabaseGetsEpochAndSchedulerReplayRunsOncePerVersion() {
        val db = TestPg.migratedDb()
        var replays = 0
        val seq = StartupSequence(db, schedulerVersion = "v1", replay = { replays++ })
        seq.runOnce()
        val epoch = db.state(ServerStateKeys.LOG_EPOCH)
        assertEquals(36, epoch?.length)
        assertEquals("v1", db.state(ServerStateKeys.CORE_SCHEDULER_VERSION))
        assertEquals(1, replays)
        assertNull(db.state(ServerStateKeys.HLC_LAST)) // reserved; T202 fills it in

        seq.runOnce() // same version: no second replay, same epoch
        assertEquals(1, replays)
        assertEquals(epoch, db.state(ServerStateKeys.LOG_EPOCH))

        StartupSequence(db, schedulerVersion = "v2", replay = { replays++ }).runOnce()
        assertEquals(2, replays)
        assertEquals("v2", db.state(ServerStateKeys.CORE_SCHEDULER_VERSION))
        db.close()
    }

    @Test
    fun failedReplayLeavesTheVersionRowUnchanged() {
        val db = TestPg.migratedDb()
        StartupSequence(db, schedulerVersion = "v1").runOnce()
        val failing = StartupSequence(db, schedulerVersion = "v2", replay = { error("crash") })
        runCatching { failing.runOnce() }
        assertEquals("v1", db.state(ServerStateKeys.CORE_SCHEDULER_VERSION))
        db.close()
    }

    @Test
    fun restorePendingIsFinalizedBeforeTheSchedulerCheck() {
        val db = TestPg.migratedDb()
        StartupSequence(db, schedulerVersion = "v1").runOnce()
        val oldEpoch = db.state(ServerStateKeys.LOG_EPOCH)

        val now = Instant.parse("2026-11-12T00:00:00Z")
        val farAhead = now.plus(Duration.ofHours(48)).toEpochMilli() // above now + 24 h
        val lastSeq = db.txBlocking { dsl ->
            dsl.execute(
                "insert into app.change (op_id, op_type, entity_type, entity_id, device_id, hlc, payload) " +
                    "values (?::uuid, 'note.created', 'note', 'x', ?::uuid, ?, '{}'::jsonb)",
                Uuid7.generate().toString(), SERVER_DEVICE_ID, StartupSequence.formatHlc(farAhead, 3),
            )
            dsl.execute(
                "insert into app.server_state (key, value, updated_at) values ('restore_pending', '1', now())",
            )
            dsl.fetchValue("select max(seq) from app.change") as Long
        }

        var seenInReplay: Pair<String?, Boolean>? = null
        val seq = StartupSequence(db, schedulerVersion = "v2", now = { now }, replay = { tx ->
            // the restore must already be committed when the scheduler check runs
            val pending = tx.fetchOptional("select 1 from app.server_state where key = 'restore_pending'").isPresent
            val epoch = tx.fetchOptional("select value from app.server_state where key = 'log_epoch'")
                .map { it.get(0, String::class.java) }.orElse(null)
            seenInReplay = epoch to pending
        })
        seq.runOnce()

        val newEpoch = db.state(ServerStateKeys.LOG_EPOCH)
        assertNotEquals(oldEpoch, newEpoch)
        assertEquals(newEpoch to false, seenInReplay)
        assertNull(db.state(ServerStateKeys.RESTORE_PENDING))
        assertEquals(lastSeq.toString(), db.state(ServerStateKeys.DURABLE_SEQ))
        assertEquals(StartupSequence.formatHlc(farAhead, 3), db.state(ServerStateKeys.HLC_LAST))

        db.close()
    }

    @Test
    fun startupWaitsForTheSyncLock() {
        val db = TestPg.migratedDb()
        val seq = StartupSequence(db, schedulerVersion = "v1")
        val finished = CountDownLatch(1)
        db.dataSource.connection.use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("select pg_advisory_xact_lock($SYNC_LOCK)") }
            val t = Thread { seq.runOnce(); finished.countDown() }.also { it.start() }
            assertFalse(finished.await(700, TimeUnit.MILLISECONDS), "startup must block on lock 7033001")
            holder.commit()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            t.join()
        }
        db.close()
    }
}
