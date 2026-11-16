package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.AnswerTestSupport.C1
import kotobaya.android.shell.api.review.AnswerTestSupport.C2
import kotobaya.android.shell.api.review.AnswerTestSupport.HOUR
import kotobaya.android.shell.api.review.AnswerTestSupport.T0
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.cards
import kotobaya.android.shell.api.review.AnswerTestSupport.device
import kotobaya.android.shell.api.review.AnswerTestSupport.iso
import kotobaya.android.shell.api.review.AnswerTestSupport.json
import kotobaya.android.shell.api.review.AnswerTestSupport.problem
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.shell.api.review.AnswerTestSupport.undo
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.store.bookkeeping

/**
 * SHELL-L3 acceptance `ClockSkewTest` (SHELL.md L-3 table, L-5, 10.3, 10.4; SYNC.md 3.4): a wrong clock gives the
 * documented `422` at `/reviewed_at` and nothing is minted, applied or queued — so nothing with a wrong time can reach
 * the log — and the same request succeeds once the clock is right (a refused id is not burnt).
 */
class AnswerClockSkewTest {
    private val minute = 60_000L

    private fun assertNothingWritten(d: ParityDevice, minted: Int = 0) {
        assertEquals(minted, d.minted().size, "nothing minted")
        assertEquals(minted, d.reviews().size, "no review row")
        assertEquals(minted, d.localChanges, "no sync trigger")
    }

    /** The answer was stamped by a clock 10 minutes ahead of the device's now (the clock was corrected in between). */
    @Test
    fun anAnswerStampedTenMinutesAheadOfTheDeviceClockIsInFuture() {
        val d = device()
        val before = d.cards()
        val res = d.answer(reviewId(1), C1, at = T0 + 1_000, extra = """"reviewed_at":"${iso(T0 + 1_000 + 10 * minute)}"""")
        assertEquals("422 /reviewed_at in_future", res.problem())
        assertEquals("422 /reviewed_at in_future", d.answer(reviewId(1), C1, extra = """"reviewed_at":"${iso(T0 + 1_000 + 5 * minute + 1)}"""").problem())
        assertNothingWritten(d)
        assertEquals(before, d.cards())
        // exactly 5 minutes is within the tolerance, and the refused id is still usable
        val ok = d.answer(reviewId(1), C1, extra = """"reviewed_at":"${iso(T0 + 1_000 + 5 * minute)}"""")
        assertEquals(200, ok.status)
        assertEquals(JsonString(iso(T0 + 1_000 + 5 * minute)), ParityJson.at(ok.json(), "/review/reviewed_at"))
    }

    /**
     * The device clock itself is 10 minutes ahead of the server's: not detectable offline and under the 24 h block, so
     * the answer is logged with the device's time (SHELL.md 10.4: handled by the sync engine, not here). Once a
     * response has shown it more than 24 h ahead, every write is `in_future`.
     */
    @Test
    fun aDeviceClockAheadOfTheServerIsRefusedOnlyOnceItIsKnownToBeMoreThanADayAhead() {
        val d = device()
        d.store.transaction { it.bookkeeping.serverSkewMs = -10 * minute }
        val ok = d.answer(reviewId(1), C1, at = T0 + 10 * minute)
        assertEquals(200, ok.status)
        assertEquals(JsonString(iso(T0 + 10 * minute)), ParityJson.at(ok.json(), "/review/reviewed_at"))

        d.store.transaction {
            it.bookkeeping.serverSkewMs = -25 * HOUR
            it.bookkeeping.clockBlocked = true
        }
        val cards = d.cards()
        assertEquals("422 /reviewed_at in_future", d.answer(reviewId(2), C2, at = T0 + 25 * HOUR).problem())
        assertEquals("422 /reviewed_at in_future", d.undo(reviewId(1)).problem())
        assertNothingWritten(d, minted = 1)
        assertEquals(cards, d.cards())
        // an answer the replica already holds needs no mint: still 200
        assertEquals(200, d.answer(reviewId(1), C1).status)
    }

    /** G2: the clock is two days behind a time the device has already seen (the seed's HLCs). */
    @Test
    fun aDeviceClockTwoDaysBehindIsBeforeCardAndNothingIsMinted() {
        val d = device()
        assertEquals(200, d.answer(reviewId(1), C1, at = T0 + 1_000).status)
        val cards = d.cards()
        val behind = T0 - 48 * HOUR

        assertEquals("422 /reviewed_at before_card", d.answer(reviewId(2), C2, at = behind).problem())
        assertEquals("422 /reviewed_at before_card", d.answer(reviewId(2), C2, extra = """"mode":"drill"""").problem())
        assertEquals("422 /reviewed_at before_card", d.undo(reviewId(1)).problem())
        // the SPA's own stamp of the real time is then more than 5 minutes after the device's now
        assertEquals("422 /reviewed_at in_future", d.answer(reviewId(2), C2, extra = """"reviewed_at":"${iso(T0 + 2_000)}"""").problem())
        assertNothingWritten(d, minted = 1)
        assertEquals(cards, d.cards())
        assertEquals(200, d.answer(reviewId(1), C1).status, "a retry of a stored answer mints nothing and still answers")

        // the clock is fixed: the refused requests go through, with the right time
        val ok = d.answer(reviewId(2), C2, at = T0 + 5_000)
        assertEquals(200, ok.status)
        assertEquals(JsonString(iso(T0 + 5_000)), ParityJson.at(ok.json(), "/review/reviewed_at"))
        assertEquals(200, d.undo(reviewId(1), at = T0 + 6_000).status)
        assertEquals(3, d.minted().size)
    }

    /** G1: a clock reset to 1970 or 2000. */
    @Test
    fun aDeviceClockBefore2020IsBeforeFloor() {
        val d = device()
        assertEquals("422 /reviewed_at before_floor", d.answer(reviewId(1), C1, at = 946_684_800_000L).problem())
        assertEquals("422 /reviewed_at before_floor", d.answer(reviewId(1), C1, at = 1_000L).problem())
        assertNothingWritten(d)
    }

    /** A late submission: up to 24 h before the card is accepted, older is `before_card`; the floor is checked first. */
    @Test
    fun theLowerBoundsOfAnExplicitReviewedAt() {
        val d = device()
        val created = (d.cards().first()["created_at"] as kotobaya.contracts.sync.JsonNumber).value.toLong()
        assertEquals("422 /reviewed_at before_card", d.answer(reviewId(1), C1, at = T0 + 1_000, extra = """"reviewed_at":"${iso(created - 24 * HOUR - 1)}"""").problem())
        assertEquals("422 /reviewed_at before_floor", d.answer(reviewId(1), C1, extra = """"reviewed_at":"2019-12-31T23:59:59.999Z"""").problem())
        assertEquals("422 /reviewed_at invalid_value", d.answer(reviewId(1), C1, extra = """"reviewed_at":"yesterday"""").problem())
        assertEquals("422 /reviewed_at invalid_type", d.answer(reviewId(1), C1, extra = """"reviewed_at":1795168800000""").problem())
        assertNothingWritten(d)
        assertEquals(200, d.answer(reviewId(1), C1, extra = """"reviewed_at":"${iso(created - 24 * HOUR)}"""").status)
    }
}
