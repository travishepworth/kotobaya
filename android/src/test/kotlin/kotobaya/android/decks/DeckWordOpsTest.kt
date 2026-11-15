package kotobaya.android.decks

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.T0
import kotobaya.android.notes.creator
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.store.UuidV7Source

/** AND-DECK-INSPECT: bulk status actions mint one op per entry in one transaction, and a repeat mints nothing. */
class DeckWordOpsTest {
    private val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
    private val membership = DeckMembership(d.store, d.stack.minter, UuidV7Source(Random(5)), d.clock)
    private val ops = SqlDeckWordActions(d.store, d.stack.minter, membership, creator(d))

    private fun override(seq: Long): String? = d.store.read { tx ->
        tx.learning.get(LearningTable.WORD_STATUS, listOf(JsonNumber(seq.toDouble())))?.get("override_status")
            ?.let { (it as? JsonString)?.value }
    }

    @Test
    fun bulkKnownMintsOnePerEntryAndIsIdempotentAndResetClears() {
        assertEquals(2, ops.setStatus(listOf(101L, 102L, 101L), "known"))
        assertEquals("known", override(101))
        assertEquals("known", override(102))
        assertEquals(0, ops.setStatus(listOf(101L, 102L), "known"))

        assertEquals(2, ops.setStatus(listOf(101L, 103L), "ignored"))
        assertEquals("ignored", override(101))

        assertEquals(3, ops.setStatus(listOf(101L, 102L, 103L), null))
        assertNull(override(101))
        assertNull(override(102))
        assertEquals(0, ops.setStatus(listOf(101L, 102L, 103L), null))
    }
}
