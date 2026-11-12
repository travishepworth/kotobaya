package kotobaya.sync.restore

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.server.StartupSequence
import kotobaya.sync.fuzz.SimDevice
import kotobaya.sync.fuzz.wallOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNC.md 6.2 / 6.3 on the server: `durable_seq` reporting, the backups-disabled setting, restore finalization and its clock. */
class RestoreTest {
    private val start = 0x0193a1b2c000L

    private fun notes(s: StoryServer, d: SimDevice, n: Int) {
        repeat(n) {
            d.make(OpType.NOTE_CREATED, mapOf("id" to d.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB))
        }
        d.sync(s.sim)
    }

    @Test
    fun durableSeqStaysAtTheLastBackupMarkerWhileBackupsAreEnabled() = StoryServer(start).use { s ->
        val a = SimDevice(A).also { it.pt = start }
        notes(s, a, 3)
        assertEquals(0L, s.sim.pull(0)["durable_seq"].asLong(), "no backup yet")
        s.backup() // verified backup of seq 1..3
        notes(s, a, 2)
        val push = s.sim.push(A, listOf(a.make(OpType.NOTE_CREATED, mapOf("id" to a.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB))))
        assertEquals(6L, push["high_water_seq"].asLong())
        assertEquals(3L, push["durable_seq"].asLong(), "push reports the marker")
        assertEquals(3L, s.sim.pull(0)["durable_seq"].asLong(), "pull reports the marker")
        // A marker above the high-water mark (should never happen) is not reported above it.
        s.db.txBlocking { it.execute("update app.server_state set value = '99' where key = 'durable_seq'") }
        assertEquals(6L, s.sim.pull(0)["durable_seq"].asLong())
    }

    @Test
    fun backupsDisabledMakesDurableSeqFollowTheHighWaterSeq() = StoryServer(start, backupsDisabled = true).use { s ->
        val a = SimDevice(A).also { it.pt = start }
        notes(s, a, 3)
        assertEquals(3L, s.sim.pull(0)["durable_seq"].asLong())
        val push = s.sim.push(A, listOf(a.make(OpType.NOTE_CREATED, mapOf("id" to a.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB))))
        assertEquals(4L, push["durable_seq"].asLong())
        // So devices purge their acked rows (5.4) once pulled.
        a.sync(s.sim)
        assertTrue(a.outbox.isEmpty(), "acked rows are purged with durable_seq = high_water_seq")
    }

    @Test
    fun theBackupsDisabledSettingIsReadFromTheEnvironmentAsTheHelmChartSetsIt() {
        assertTrue(DurableSeq.backupsDisabled(mapOf("BACKUPS_DISABLED" to "true")))
        assertTrue(DurableSeq.backupsDisabled(mapOf("BACKUPS_DISABLED" to "TRUE")))
        assertFalse(DurableSeq.backupsDisabled(mapOf("BACKUPS_DISABLED" to "false")))
        assertFalse(DurableSeq.backupsDisabled(emptyMap()))
        assertTrue(SyncWiring.config(env = mapOf("BACKUPS_DISABLED" to "true")).backupsDisabled)
        assertFalse(SyncWiring.config(env = emptyMap()).backupsDisabled)
        assertEquals(5L, DurableSeq.reported(2, 5, backupsDisabled = true))
        assertEquals(2L, DurableSeq.reported(2, 5, backupsDisabled = false))
        assertEquals(5L, DurableSeq.reported(9, 5, backupsDisabled = false))
    }

    /** "Freeze A → T202/T104": restore finalisation raises `hlc_last` so the server never mints behind its pre-restore clock. */
    @Test
    fun restoreFinalisationRaisesHlcLastSoTheServerNeverMintsBehindItsPreRestoreClock() = StoryServer(start).use { s ->
        val a = SimDevice(A).also { it.pt = start }
        val b = SimDevice(B).also { it.pt = start }
        notes(s, a, 2)
        val backup = s.backup()
        val epoch1 = s.serverState("log_epoch")
        // After the backup: B, 23 h ahead (within tolerance), adds the same word as A; the server merges it,
        // minting merge HLCs just above B's future clock. All of this is lost in the restore.
        b.pt = start + 23 * 3_600_000L
        val seq = 77L
        a.make(OpType.NOTE_CREATED, mapOf("id" to a.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to seq))
        a.sync(s.sim)
        b.make(OpType.NOTE_CREATED, mapOf("id" to b.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to seq))
        b.push(s.sim)
        val lostMax = s.db.txBlocking { it.fetchValue("select max(hlc::text collate \"C\") from app.change") as String }
        assertEquals(SERVER, lostMax.substring(18), "the newest lost hlc is a server-minted merge")
        val lastBefore = s.serverState("hlc_last")!!

        s.pt = start + 1000
        s.restore(backup)
        val raised = s.serverState("hlc_last")!!
        assertNotEquals(epoch1, s.serverState("log_epoch"), "new log epoch")
        assertEquals("2", s.serverState("durable_seq"), "durable_seq = the restored max(seq)")
        assertNull(s.serverState("restore_pending"))
        assertTrue(raised.substring(0, 17) > lostMax.substring(0, 17), "above every lost hlc")
        assertTrue(raised.substring(0, 17) >= lastBefore.substring(0, 17), "never below the pre-restore clock")
        assertEquals(start + 1000 + Reserved.DAY_MS, wallOf(raised), "(now + 24 h, 0)")

        // The next server-minted op is above every HLC the lost log held.
        a.pt = start + 2000
        b.pt = start + 2000
        a.make(OpType.NOTE_CREATED, mapOf("id" to a.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 900))
        b.make(OpType.NOTE_CREATED, mapOf("id" to b.newId(1), "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 900))
        s.pt = start + 2000
        a.push(s.sim); b.push(s.sim)
        val merge = s.db.txBlocking { it.fetchValue("select min(hlc::text collate \"C\") from app.change where op_type = 'note.merged'") as String }
        assertTrue(merge > lostMax, "server mints above its pre-restore clock: $merge vs $lostMax")

        // A second instance starting later does not finalize again.
        val epoch2 = s.serverState("log_epoch")
        StartupSequence(s.db).runOnce()
        assertEquals(epoch2, s.serverState("log_epoch"))
    }

    companion object {
        const val A = "01931f3e-0000-7000-8000-0000000000a1"
        const val B = "01931f3e-0000-7000-8000-0000000000b2"
        const val SERVER = "00000000-0000-7000-8000-000000000001"
    }
}
