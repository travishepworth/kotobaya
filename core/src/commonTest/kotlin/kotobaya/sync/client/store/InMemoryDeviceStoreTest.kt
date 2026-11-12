package kotobaya.sync.client.store

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InMemoryDeviceStoreTest {
    private val store = InMemoryDeviceStore(mutableListOf<String>()) { it.toMutableList() }

    private fun row(id: String, hlc: String, state: PendingState = PendingState.PENDING, seq: Long? = null) =
        PendingOpRow(id, "settings.set", 1, "settings", "k", hlc, "{}", 0L, state, ackedSeq = seq)

    @Test
    fun aThrowingTransactionLeavesNothingBehind() {
        assertFailsWith<IllegalStateException> {
            store.transaction { tx ->
                tx.learning.add("effect")
                tx.outbox.insert(row("a", "h1"))
                tx.syncState.put(SyncStateKeys.PULL_SEQ, "9")
                error("crash")
            }
        }
        assertEquals(emptyList(), store.rows())
        assertEquals(emptyMap(), store.syncState())
        assertEquals(emptyList(), store.learning())
    }

    @Test
    fun nestedTransactionsJoinTheOuterOne() {
        assertFailsWith<IllegalStateException> {
            store.transaction { tx ->
                store.transaction { inner -> inner.outbox.insert(row("a", "h1")) }
                assertEquals(1, tx.outbox.count(PendingState.PENDING))
                error("crash")
            }
        }
        assertEquals(emptyList(), store.rows())
    }

    @Test
    fun outboxOrdersByHlcAndPurgesOnlyAckedRowsUpToTheBound() {
        store.transaction { tx ->
            tx.outbox.insert(row("c", "h3"))
            tx.outbox.insert(row("a", "h1"))
            tx.outbox.insert(row("b", "h2"))
            assertFailsWith<IllegalStateException> { tx.outbox.insert(row("a", "h9")) }
            assertEquals(listOf("a", "b"), tx.outbox.inState(PendingState.PENDING, 2).map { it.opId })
            tx.outbox.markAcked("a", 5)
            tx.outbox.markAcked("b", 7)
            tx.outbox.markRejected("c", "immutable: no")
            tx.outbox.incrementAttempts(listOf("c", "missing"))
            assertEquals(1, tx.outbox.deleteAckedThrough(6))
            assertEquals(listOf("b"), tx.outbox.inState(PendingState.ACKED).map { it.opId })
            tx.outbox.markPending("b", 0)
            assertEquals(null, tx.outbox.get("b")?.ackedSeq)
            assertEquals(1, tx.outbox.get("c")?.attempts)
        }
        assertEquals(listOf("b", "c"), store.rows().map { it.opId })
    }

    @Test
    fun uuidV7IsCanonicalWithVersionAndVariantAndTimeOrdered() {
        val src = UuidV7Source(Random(4))
        val a = src.newOpId(1_733_584_666_000L)
        val b = src.newOpId(1_733_584_666_001L)
        val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(pattern.matches(a), a)
        assertTrue(a.startsWith("0193a1b2-"), a)
        assertTrue(a < b)
    }
}
