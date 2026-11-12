package kotobaya.sync.restore

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.sync.fuzz.ServerProjection
import kotobaya.sync.fuzz.SimDevice
import kotobaya.sync.fuzz.wallOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SYNC.md 12.5 (`tools/validate.py` `restore_story`) against the real server: a restore loses the
 * log's tail; the new `log_epoch` makes both phones reset, re-push and rebuild; nothing is lost and
 * all three converge. Also the 6.2 step 3 clock: the restored server never mints behind its own
 * pre-restore clock.
 */
class RestoreStoryTest {
    private val start = 0x0193a1b2c000L
    private val s = StoryServer(start)

    @AfterTest fun close() = s.close()

    @Test
    fun workedExample12_5_serverRestoredFromABackup() {
        val a = SimDevice(A)
        val b = SimDevice(B)
        var t = start
        fun tick() {
            t += 1000
            s.pt = t; a.pt = t; b.pt = t
        }
        tick()
        val note = a.newId(1)
        val card = a.newId(2)
        a.make(OpType.NOTE_CREATED, mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 1358280))
        a.make(OpType.CARD_CREATED, mapOf("id" to card, "note_id" to note, "kind" to "meaning"))
        a.sync(s.sim)
        b.sync(s.sim)
        val epoch1 = a.logEpoch
        val backup = s.backup() // covers seq 1..2; durable_seq = 2
        val reviews = (1..5).map {
            tick()
            a.newId(3).also { rid ->
                a.make(
                    OpType.REVIEW_LOGGED,
                    mapOf("id" to rid, "card_id" to card, "reviewed_at" to a.pt, "utc_offset_min" to 540, "review_type" to "scheduled", "rating" to 3, "button" to "good"),
                )
            }
        }
        a.sync(s.sim)
        assertEquals(2L, a.durableSeq)
        assertEquals(5, a.outbox.count { it.state == "acked" }, "acked_seq 3..7 > durable_seq 2: rows kept")
        val hlcBefore = s.serverState("hlc_last")!!
        val maxLost = s.db.txBlocking { it.fetchValue("select max(hlc::text collate \"C\") from app.change") as String }

        tick()
        s.restore(backup) // back to seq 2
        val raised = s.serverState("hlc_last")!!
        assertTrue(raised.substring(0, 17) >= hlcBefore.substring(0, 17), "the server's hlc never goes backwards")
        assertTrue(raised.substring(0, 17) > maxLost.substring(0, 17), "raised above every lost hlc")
        assertTrue(wallOf(raised) >= t + Reserved.DAY_MS, "raised to now + 24 h")
        assertEquals("2", s.serverState("durable_seq"))
        assertEquals(null, s.serverState("restore_pending"))

        tick()
        b.make(OpType.NOTE_UPDATED, mapOf("id" to note, "set" to mapOf("tags" to listOf("restored"))))
        b.make(OpType.CARD_SUSPENDED, mapOf("id" to card))
        b.sync(s.sim)
        assertNotEquals(epoch1, b.logEpoch, "B noticed the new epoch")
        repeat(2) {
            tick()
            a.sync(s.sim)
            b.sync(s.sim)
        }

        val stored = s.db.txBlocking { dsl -> dsl.fetch("select id::text from app.review_log").map { it.get(0, String::class.java) }.toSet() }
        assertEquals(reviews.toSet(), stored, "none of A's reviews is lost")
        val sp = ServerProjection.of(s.db)
        for (d in listOf(a, b)) {
            assertEquals(emptyList(), ServerProjection.diff(sp, d.r.projection()), "device ${d.id.takeLast(2)} converges")
            assertTrue(!d.rebuildPending && d.outbox.all { it.state == "acked" }, "device ${d.id.takeLast(2)} has unfinished work")
        }
        assertEquals(true, (sp.getValue("card")[card] as Map<*, *>)["suspended"], "B's edit after the restore is there")
    }

    companion object {
        const val A = "01931f3e-0000-7000-8000-0000000000a1"
        const val B = "01931f3e-0000-7000-8000-0000000000b2"
    }
}
