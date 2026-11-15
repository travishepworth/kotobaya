package kotobaya.android.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.NoteRequest
import kotobaya.android.notes.T0
import kotobaya.android.notes.creator
import kotobaya.android.review.actions.WordCardSuspension
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.sync.client.store.PendingState

/** ACTIONS-PARITY-AND: suspending a word's cards mints one op per live card, and unsuspend restores them. */
class WordCardSuspensionTest {
    private val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
    private val s = WordCardSuspension(d.store, d.stack.minter)
    private fun pending(): Int = d.store.read { it.outbox.count(PendingState.PENDING) }

    @Test
    fun suspendMintsOnePerLiveCardAndUnsuspendRestores() {
        assertIs<CreateResult.Created>(creator(d).create(NoteRequest(word = "食べる", reading = "たべる")))
        val seq = 1358280L
        val cards = s.cardsOf(seq)
        assertTrue(cards.size >= 2 && cards.none { it.suspended })

        val before = pending()
        assertEquals(cards.size, s.setForWord(seq, true))
        assertEquals(cards.size, pending() - before)
        assertTrue(s.cardsOf(seq).all { it.suspended })
        assertEquals(0, s.setForWord(seq, true), "already suspended mints nothing")

        assertEquals(cards.size, s.setForCards(cards.map { it.cardId }, false))
        assertEquals(cards, s.cardsOf(seq))
        assertEquals(emptyList(), s.cardsOf(999L))
    }
}
