package kotobaya.sync

import kotobaya.contracts.sync.OpType.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * C-32 (OPS.md `deck.deleted`): a pushed `deck.deleted` for a media deck is `invalid_target` while
 * the media is live and accepted once it is tombstoned — in the same batch after its
 * `media.deleted`, in a later push, or after another replica deleted the media.
 */
class MediaDeckDeleteTest : SyncFixture() {
    private val media = "0193a1b2-0007-7000-8000-000000000001"
    private val base = 0x0193a1b2c000L

    private fun mediaDeleted(hlc: Long, device: String = A) = op(MEDIA_DELETED, device, hlc, """{"id":"$media"}""")
    private fun deckDeleted(hlc: Long, device: String = A) = op(DECK_DELETED, device, hlc, """{"id":"$media"}""")

    private fun createMedia() {
        emit(MEDIA_CREATED, """{"id":"$media","title":"Frieren","kind":"anime","source":"file"}""")
        assertEquals("media", row("deck", "id" to media)!!["type"].asText())
    }

    private fun deckDeletedAt(): Long? = SyncJson.ms(row("deck", "id" to media)!!["deleted_at"])

    /** The `deck.deleted` rows of the log: (device_id, caused_by_op_id). */
    private fun deckDeletes(): List<Pair<String, String?>> = db.txBlocking {
        it.fetch("select device_id::text, caused_by_op_id::text from app.change where op_type = 'deck.deleted' order by seq")
            .map { r -> (r.get(0) as String) to (r.get(1) as String?) }
    }

    /** The hlc wall time of the highest-hlc `deck.deleted` in the log (the clock rule's winner). */
    private fun highestDeleteWall(): Long = db.txBlocking {
        wall(it.fetchValue("select max(hlc) from app.change where op_type = 'deck.deleted'") as String)
    }

    @Test
    fun deckDeletedForAMediaDeckWithLiveMediaIsStillRejectedInvalidTarget() {
        createMedia()
        val r = push(principal(A), deckDeleted(base + 10)).single()
        assertRejected(RejectCode.INVALID_TARGET, r)
        assertNull(deckDeletedAt())
        assertEquals(emptyList(), deckDeletes())
    }

    @Test
    fun mediaDeletedThenDeckDeletedInOneBatchAreBothAccepted() {
        createMedia()
        val md = mediaDeleted(base + 10)
        val res = push(principal(A), md, deckDeleted(base + 11))
        res.forEach(::assertAccepted)
        assertNotNull(deckDeletedAt())
        // The server's own cascade (caused by the media.deleted) is logged first, then the device's op.
        assertEquals(listOf(SERVER to md["op_id"].asText(), A to null), deckDeletes())
        assertEquals(highestDeleteWall(), deckDeletedAt())
        // Idempotent: the client's retry after a lost response changes nothing.
        val before = row("deck", "id" to media)
        val again = push(principal(A), md, deckDeleted(base + 11, A).put("op_id", res[1].opId))
        assertEquals(res.map { PushResult(it.opId, PushResult.DUPLICATE, it.seq) }, again)
        assertEquals(before, row("deck", "id" to media))
    }

    @Test
    fun mediaDeletedThenDeckDeletedInSeparatePushesAreBothAccepted() {
        createMedia()
        assertAccepted(push(principal(A), mediaDeleted(base + 10)).single())
        assertAccepted(push(principal(A), deckDeleted(base + 11)).single())
        assertEquals(2, deckDeletes().size)
        assertEquals(highestDeleteWall(), deckDeletedAt())
    }

    @Test
    fun deckDeletedIsAcceptedAfterAnotherReplicaDeletedTheMedia() {
        createMedia()
        assertAccepted(push(principal(B), mediaDeleted(base + 20, B)).single())
        val serverDeleteAt = deckDeletedAt()
        assertNotNull(serverDeleteAt)
        // A's older delete (offline) loses the register: deleted_at stays at the highest delete clock.
        assertAccepted(push(principal(A), deckDeleted(base + 5)).single())
        assertEquals(serverDeleteAt, deckDeletedAt())
        assertEquals(highestDeleteWall(), deckDeletedAt())
    }

    @Test
    fun deckDeletedBeforeItsMediaDeletedIsRejectedThenConvergesAndRetrySucceeds() {
        createMedia()
        val a = principal(A)
        // Out of order within one batch (ascending hlc, wrong mint order): the deck op meets live media.
        val res = push(a, deckDeleted(base + 10), mediaDeleted(base + 11))
        assertRejected(RejectCode.INVALID_TARGET, res[0])
        assertAccepted(res[1])
        // Converged anyway: the server tombstoned the deck itself, caused by the media.deleted.
        assertNotNull(deckDeletedAt())
        assertEquals(listOf(SERVER to res[1].opId), deckDeletes())
        val afterCascade = deckDeletedAt()
        // Retry (SYNC.md 10.2) of the rejected op: same op, now accepted; the older hlc does not move deleted_at.
        val retry = push(a, deckDeleted(base + 10).put("op_id", res[0].opId)).single()
        assertAccepted(retry)
        assertEquals(afterCascade, deckDeletedAt())
        assertEquals(highestDeleteWall(), deckDeletedAt())
    }
}
