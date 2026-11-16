package kotobaya.android.shell.api.review

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.QueueTestSupport.at
import kotobaya.android.shell.api.review.QueueTestSupport.device
import kotobaya.android.shell.api.review.QueueTestSupport.items
import kotobaya.android.shell.api.review.QueueTestSupport.json
import kotobaya.android.shell.api.review.QueueTestSupport.liveNotesOf
import kotobaya.android.shell.api.review.QueueTestSupport.long
import kotobaya.android.shell.api.review.QueueTestSupport.queue
import kotobaya.android.shell.api.review.QueueTestSupport.str
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.contracts.sync.JsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L2 acceptance "MaterializeIdempotentTest" (C-27, SHELL.md L-2/L-4): fetches create one note per drawn deck
 * entry however often and however concurrently they run, mint as this device, and every minted op validates against
 * `op.schema.json`. Seed: `queue-materialize-count` (a user deck with entries 1000220, 1000440, 1000450 and no
 * notes; `new_per_day` 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class QueueMaterializeIdempotentTest {
    private val drawn = listOf(1000220L, 1000440L)

    private fun ParityDevice.noteCreated(): List<JsonObject> = minted().filter { str(it.members["op_type"]) == "note.created" }

    private fun assertOneNotePerDrawnEntry(d: ParityDevice) {
        drawn.forEach { assertEquals(1, d.liveNotesOf(it).size, "live notes of $it") }
        assertEquals(0, d.liveNotesOf(1000450L).size, "an entry the budget did not draw is not materialized")
        assertEquals(drawn.size, d.noteCreated().size)
    }

    @Test
    fun twoConsecutiveFetchesCreateOneNotePerDrawnEntry() {
        val d = device("queue-materialize-count")
        val first = d.queue().json()
        assertEquals(2, first.items().size)
        assertEquals(0L, first.long("/to_materialize"))
        assertEquals(2L, first.long("/counts/new"))
        val afterFirst = d.minted().size
        assertEquals(1, d.localChanges, "one local change for the one materialization")

        val second = d.queue(at = d.clock.now + 1_000).json()
        assertEquals(first.items().map { it.at("/card/id") }, second.items().map { it.at("/card/id") })
        assertEquals(afterFirst, d.minted().size, "the second fetch mints nothing")
        assertOneNotePerDrawnEntry(d)
    }

    @Test
    fun twoConcurrentFetchesCreateOneNotePerDrawnEntry() {
        val d = device("queue-materialize-count")
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val results = (1..2).map { pool.submit<Int> { start.await(); d.queue().status } }
        start.countDown()
        val statuses = results.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        // the bodies are not compared: the harness's one in-memory connection lets a build read the other fetch's
        // uncommitted rows; what must hold under any interleaving is that no entry gets a second note
        assertEquals(listOf(200, 200), statuses)
        assertOneNotePerDrawnEntry(d)
        // whichever fetch lost the race serves the winner's cards (its own rebuild), never a second note
        val third = d.queue().json()
        assertEquals(2, third.items().size)
        assertOneNotePerDrawnEntry(d)
    }

    @Test
    fun mintedOpsAreTheDevicesAndValidateAgainstTheOpSchema() {
        val d = device("queue-materialize-count")
        d.queue()
        val ops = d.minted()
        assertTrue(ops.isNotEmpty())
        ops.forEach { op ->
            assertTrue(isValidOp(op), "invalid op ${op.members["op_type"]}")
            assertEquals(QueueTestSupport.DEVICE, str(op.members["device_id"]))
        }
        // per drawn entry: note.created {vocab, entry_seq, fields {}, origin deck} and its card.created
        val notes = d.noteCreated().map { it.members["payload"] as JsonObject }
        assertEquals(drawn, notes.map { (it.members["entry_seq"] as kotobaya.contracts.sync.JsonNumber).value.toLong() })
        notes.forEach { assertEquals("deck", str(it.members["origin"])) }
        assertEquals(setOf("note.created", "card.created"), ops.map { str(it.members["op_type"]) }.toSet())
    }

    @Test
    fun whileMaterializingIsNotAllowedTheEntriesAreCountedAndNothingIsMinted() {
        val d = device("queue-materialize-count")
        d.failures.failures = 1 // a failed sync cycle since the last complete pull
        val body = d.queue().json()
        assertEquals(0, body.items().size)
        assertEquals(2L, body.long("/counts/new"))
        assertEquals(2L, body.long("/to_materialize"))
        assertEquals(0, d.minted().size)

        d.failures.failures = 0
        val allowed = d.queue().json()
        assertEquals(2, allowed.items().size)
        assertEquals(0L, allowed.long("/to_materialize"))
    }

    @Test
    fun aFetchWithoutNewInKindsMaterializesNothingAndCountsTheEntries() {
        val d = device("queue-materialize-count")
        val body = d.queue("kinds=review,learning").json()
        assertEquals(0, body.items().size)
        assertEquals(2L, body.long("/counts/new"))
        assertEquals(0L, body.long("/to_materialize"))
        assertEquals(0, d.minted().size)
    }
}
