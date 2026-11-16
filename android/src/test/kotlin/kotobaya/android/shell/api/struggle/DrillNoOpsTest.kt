package kotobaya.android.shell.api.struggle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.review.AnswerTestSupport.card
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.shell.api.struggle.StruggleTestSupport.C1
import kotobaya.android.shell.api.struggle.StruggleTestSupport.C2
import kotobaya.android.shell.api.struggle.StruggleTestSupport.call
import kotobaya.android.shell.api.struggle.StruggleTestSupport.mintedCount
import kotobaya.android.shell.api.struggle.StruggleTestSupport.snapshot
import kotobaya.android.shell.api.struggle.StruggleTestSupport.struggled
import kotobaya.contracts.sync.JsonString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SHELL-L8: `createDrillSession` stores nothing and mints nothing (SHELL.md 4.3, "No op (as the server)"), whichever
 * source; and a drill answer through the L3 path logs a review but never changes the card (SYNC.md 9.2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DrillNoOpsTest {
    @Test
    fun creatingASessionMintsNoOpAndChangesNoTable() {
        val d = struggled()
        val tables = d.snapshot()
        val minted = d.mintedCount()
        val bodies = listOf(
            """{"source":"hot_words"}""",
            """{"source":"leeches","mode":"cram"}""",
            """{"source":"confusables"}""",
            """{"source":"cards","card_ids":["$C1","$C2"]}""",
            """{"source":"query","query":"is:learning OR is:relearning"}""",
        )
        for (b in bodies) assertEquals(200, call(d, "POST", "/api/v1/drill/session", b).status, b)
        assertEquals(minted, d.mintedCount(), "no op minted")
        assertEquals(tables, d.snapshot(), "no table changed")
    }

    @Test
    fun aRejectedSessionRequestChangesNothingEither() {
        val d = struggled()
        val tables = d.snapshot()
        val minted = d.mintedCount()
        assertEquals(422, call(d, "POST", "/api/v1/drill/session", """{"source":"nope"}""").status)
        assertEquals(minted, d.mintedCount())
        assertEquals(tables, d.snapshot())
    }

    @Test
    fun aDrillAnswerLogsAReviewAndLeavesTheScheduleAlone() {
        val d = struggled()
        val before = d.card(C1)
        val reviewsBefore = d.reviews().size
        val r = call(
            d, "POST", "/api/v1/review/answer",
            """{"id":"${reviewId(90)}","card_id":"$C1","rating":3,"mode":"drill"}""", at = StruggleTestSupport.NOW,
        )
        assertEquals(200, r.status)
        assertEquals(reviewsBefore + 1, d.reviews().size)
        assertEquals(JsonString("drill"), d.reviews().single { it["id"] == JsonString(reviewId(90)) }["review_type"])
        assertEquals(before, d.card(C1))
    }
}
