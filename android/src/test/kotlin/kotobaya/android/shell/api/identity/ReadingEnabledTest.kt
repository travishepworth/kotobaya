package kotobaya.android.shell.api.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.call
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable

/** SHELL-L1 acceptance (ADR-035): toggling `cards.reading_enabled` locally re-derives word statuses as pulling the op does. */
class ReadingEnabledTest {
    private fun statuses(d: ParityDevice): List<Map<String, JsonValue>> =
        d.store.read { it.learning.all(LearningTable.WORD_STATUS) }.sortedBy { it["entry_seq"].toString() }

    @Test
    fun aLocalToggleYieldsTheSameWordStatusesAsPullingTheSameOp() {
        // vocab notes with reviews on some cards: the derived status can depend on whether reading cards count
        val seed = IdentityTestSupport.seedOf("queue-kinds-filter-counts")
        val local = IdentityTestSupport.device(seed = seed)
        val pulled = IdentityTestSupport.device(IdentityTestSupport.OTHER_DEVICE, seed)
        assertEquals(statuses(local), statuses(pulled), "same seed, same statuses")
        assertTrue(statuses(local).isNotEmpty(), "the seed has word statuses")
        var at = IdentityTestSupport.START_MS
        for (value in listOf("true", "false", "true", "null")) {
            at += 1000
            val before = local.minted().size
            local.call("PATCH", "/api/v1/settings", """{"values":{"cards.reading_enabled":$value}}""", at = at)
            pulled.pull(local.minted().drop(before), "reading_enabled=$value")
            assertEquals(statuses(pulled), statuses(local), "reading_enabled=$value")
        }
    }
}
