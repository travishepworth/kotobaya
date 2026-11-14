package kotobaya.sync

import kotobaya.contracts.sync.OpType.CARD_CREATED
import kotobaya.contracts.sync.OpType.SETTINGS_SET
import kotobaya.server.LogCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OBS-1s: every push rejection is a WARN line and every duplicate an INFO line, with op id, type and reason. */
class OpOutcomeLogTest : SyncFixture() {
    private val base = 0x0193a1b2c000L

    @Test
    fun rejectionsAreWarnAndDuplicatesInfoWithOpIdTypeAndReason() {
        val a = principal(A)
        val setting = op(SETTINGS_SET, A, base + 1, """{"key":"k.a","value":1}""")
        // A card of a note that does not exist: unknown_reference.
        val orphan = op(CARD_CREATED, A, base + 2, """{"id":"0193a1b2-0002-7000-8000-0000000000aa","note_id":"0193a1b2-0001-7000-8000-0000000000aa","kind":"meaning"}""")
        syncNow.set(java.time.Instant.ofEpochMilli(base + 10))
        assertAccepted(push(a, setting).single())

        LogCapture("kotobaya.sync.ops").use { cap ->
            val res = push(a, setting, orphan)
            assertEquals(PushResult.DUPLICATE, res[0].status)
            assertRejected(RejectCode.UNKNOWN_REFERENCE, res[1])

            val lines = cap.lines()
            val dup = lines.single { it["op_id"].asText() == setting["op_id"].asText() }
            assertEquals("INFO", dup["level"].asText())
            assertEquals("settings.set", dup["op_type"].asText())
            assertEquals("duplicate", dup["outcome"].asText())

            val rej = lines.single { it["op_id"].asText() == orphan["op_id"].asText() }
            assertEquals("WARN", rej["level"].asText())
            assertEquals("card.created", rej["op_type"].asText())
            assertEquals("unknown_reference", rej["reason"].asText())
            assertTrue(rej["reason_message"].asText().isNotEmpty())
            assertTrue(rej["message"].asText().startsWith("op rejected: unknown_reference"))
        }
    }
}
