package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.shell.api.identity.IdentityTestSupport.isValidOp
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.AnswerTestSupport.C1
import kotobaya.android.shell.api.review.AnswerTestSupport.C2
import kotobaya.android.shell.api.review.AnswerTestSupport.HOUR
import kotobaya.android.shell.api.review.AnswerTestSupport.T0
import kotobaya.android.shell.api.review.AnswerTestSupport.X
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.card
import kotobaya.android.shell.api.review.AnswerTestSupport.device
import kotobaya.android.shell.api.review.AnswerTestSupport.iso
import kotobaya.android.shell.api.review.AnswerTestSupport.json
import kotobaya.android.shell.api.review.AnswerTestSupport.problem
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.shell.api.review.AnswerTestSupport.undo
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString

/**
 * SHELL-L3 acceptance "minted ops validate against op.schema.json" (SHELL.md L-2): what `answerReview` / `undoReview`
 * put in the outbox — one op per accepted request, minted as this device, with the payload the server would build.
 */
class AnswerOpsTest {
    private fun payload(op: JsonObject): JsonObject = ParityJson.obj(op.members["payload"])

    @Test
    fun everyMintedOpValidatesAgainstTheOpSchemaAndIsTheDevices() {
        val d = device()
        d.answer(reviewId(1), C1, rating = 3, at = T0 + 1_000, extra = """"button":"okay","duration_ms":4200,"utc_offset_min":-480""")
        d.answer(reviewId(2), C1, rating = 1, at = T0 + 2_000, extra = """"button":"nothing","sentence_id":"0193b000-0004-7000-8000-000000000001"""")
        d.answer(reviewId(3), C2, rating = 4, at = T0 + 3_000, extra = """"mode":"drill"""")
        d.answer(reviewId(4), C2, rating = 2, at = T0 + 4_000, extra = """"mode":"cram","reviewed_at":"${iso(T0 - HOUR)}"""")
        d.undo(reviewId(1), at = T0 + 5_000)
        d.undo(reviewId(3), at = T0 + 6_000)
        val ops = d.minted()
        assertEquals(
            listOf("review.logged", "review.logged", "review.logged", "review.logged", "review.undone", "review.undone"),
            ops.map { ParityJson.str(it.members["op_type"]) },
        )
        ops.forEach { op ->
            assertTrue(isValidOp(op), "invalid op ${ParityJson.encode(op)}")
            assertEquals(X, ParityJson.str(op.members["device_id"]))
            assertEquals("review_log", ParityJson.str(op.members["entity_type"]))
        }
        assertEquals(ops.map { ParityJson.str(it.members["hlc"]) }.sorted(), ops.map { ParityJson.str(it.members["hlc"]) }, "hlc ascending")
        assertEquals(6, ops.map { it.members["op_id"] }.toSet().size)
        assertEquals(listOf(1, 2, 3, 4, 1, 3).map { reviewId(it) }, ops.map { ParityJson.str(it.members["entity_id"]) })
    }

    @Test
    fun thePayloadIsTheOneTheServerBuilds() {
        val d = device()
        d.answer(reviewId(1), C1, rating = 3, at = T0 + 1_000, extra = """"button":"good","duration_ms":4200,"utc_offset_min":540""")
        assertEquals(
            ParityJson.parse(
                """{"id":"${reviewId(1)}","card_id":"$C1","reviewed_at":${T0 + 1_000},"utc_offset_min":540,"review_type":"learning","rating":3,"button":"good","duration_ms":4200}""",
            ),
            payload(d.minted()[0]),
        )
        // nothing optional sent: utc_offset_min null (unknown), no button / duration_ms / sentence_id members
        d.answer(reviewId(2), C2, rating = 2, at = T0 + 2_000)
        assertEquals(
            ParityJson.parse("""{"id":"${reviewId(2)}","card_id":"$C2","reviewed_at":${T0 + 2_000},"utc_offset_min":null,"review_type":"learning","rating":2}"""),
            payload(d.minted()[1]),
        )
        d.undo(reviewId(2), at = T0 + 3_000)
        assertEquals(ParityJson.parse("""{"review_id":"${reviewId(2)}"}"""), payload(d.minted()[2]))
    }

    @Test
    fun durationIsClampedAndReviewedAtDefaultsToTheDeviceClock() {
        val d = device()
        val res = d.answer(reviewId(1), C1, at = T0 + 12_345, extra = """"duration_ms":86400000""").json()
        assertEquals(JsonNumber(600_000.0), ParityJson.at(res, "/review/duration_ms"))
        assertEquals(JsonString(iso(T0 + 12_345)), ParityJson.at(res, "/review/reviewed_at"))
        assertEquals(JsonNumber(600_000.0), payload(d.minted()[0]).members["duration_ms"])
        assertEquals(JsonNumber((T0 + 12_345).toDouble()), d.reviews().single()["reviewed_at"])
    }

    /** `mode` to `review_type`: schedule = learning until the card's state is `review`, then scheduled; drill / cram as sent. */
    @Test
    fun reviewTypeFollowsModeAndTheCardsStateAndOnlyScheduleModeMovesTheSchedule() {
        val d = device()
        val fresh = d.card(C1)
        fun type(n: Int) = ParityJson.str(d.reviews().single { it["id"] == JsonString(reviewId(n)) }["review_type"])
        d.answer(reviewId(1), C1, rating = 3, at = T0 + 1_000, extra = """"mode":"drill"""")
        d.answer(reviewId(2), C1, rating = 1, at = T0 + 2_000, extra = """"mode":"cram"""")
        assertEquals(listOf("drill", "cram"), listOf(type(1), type(2)))
        assertEquals(fresh, d.card(C1), "drill and cram are logged only")
        assertEquals(0, d.dueChanges, "nothing due changed")
        assertEquals(2, d.localChanges, "but both are synced")

        d.answer(reviewId(3), C1, rating = 4, at = T0 + 3_000) // Easy on a new card: straight to review
        assertEquals("learning", type(3))
        assertEquals(JsonString("review"), d.card(C1)["state"])
        d.answer(reviewId(4), C1, rating = 3, at = T0 + 4_000)
        assertEquals("scheduled", type(4))
        d.answer(reviewId(5), C1, rating = 1, at = T0 + 5_000) // a lapse: relearning
        assertEquals("scheduled", type(5))
        assertEquals(JsonString("relearning"), d.card(C1)["state"])
        d.answer(reviewId(6), C1, rating = 3, at = T0 + 6_000)
        assertEquals("learning", type(6))
        assertEquals(4, d.dueChanges)
    }

    /** A merge loser's id is answered on the survivor: the op, the row and the response name the survivor. */
    @Test
    fun aMergeLoserIsAnsweredOnItsSurvivor() {
        val d = device()
        val merge = QueueTestSupport.op(
            "0193b000-00ff-7000-8000-0000000000a1", "card.merged", "card", C2, T0 + 500,
            QueueTestSupport.obj("loser_id" to JsonString(C2), "survivor_id" to JsonString(C1)),
        )
        d.pull(listOf(merge), "card 2 merged into card 1")
        val res = d.answer(reviewId(1), C2, at = T0 + 1_000)
        assertEquals(200, res.status)
        assertEquals(JsonString(C1), ParityJson.at(res.json(), "/review/card_id"))
        assertEquals(JsonString(C1), ParityJson.at(res.json(), "/card/id"))
        assertEquals(JsonString(C1), payload(d.minted().single()).members["card_id"])
        assertEquals(JsonNumber(1.0), d.card(C1)["reps"])
        // the retry with the loser's id is the stored review
        assertEquals(res.json(), d.answer(reviewId(1), C2, at = T0 + 2_000).json())
        assertEquals(1, d.minted().size)
    }

    /** L-6: a suspended card is refused only in schedule mode; a tombstone is `410` in every mode. */
    @Test
    fun suspendedAndDeletedCardsAreRefusedBeforeAnythingIsMinted() {
        val d = device()
        d.pull(
            listOf(
                QueueTestSupport.op("0193b000-00ff-7000-8000-0000000000b1", "card.suspended", "card", C1, T0 + 100, QueueTestSupport.obj("id" to JsonString(C1))),
                QueueTestSupport.op("0193b000-00ff-7000-8000-0000000000b2", "card.deleted", "card", C2, T0 + 101, QueueTestSupport.obj("id" to JsonString(C2))),
            ),
            "suspend 1, delete 2",
        )
        assertEquals("409 card_not_reviewable", d.answer(reviewId(1), C1, at = T0 + 1_000).problem())
        assertEquals("410 gone", d.answer(reviewId(2), C2).problem())
        assertEquals("410 gone", d.answer(reviewId(3), C2, extra = """"mode":"drill"""").problem())
        assertEquals(0, d.minted().size)
        assertEquals(200, d.answer(reviewId(4), C1, extra = """"mode":"cram"""").status)
        assertEquals(JsonNull, d.reviews().single()["undone_at"])
    }
}
