package kotobaya.android.shell.api.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.AnswerTestSupport.B
import kotobaya.android.shell.api.review.AnswerTestSupport.C1
import kotobaya.android.shell.api.review.AnswerTestSupport.T0
import kotobaya.android.shell.api.review.AnswerTestSupport.X
import kotobaya.android.shell.api.review.AnswerTestSupport.Y
import kotobaya.android.shell.api.review.AnswerTestSupport.Z
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.card
import kotobaya.android.shell.api.review.AnswerTestSupport.cards
import kotobaya.android.shell.api.review.AnswerTestSupport.device
import kotobaya.android.shell.api.review.AnswerTestSupport.hlc
import kotobaya.android.shell.api.review.AnswerTestSupport.iso
import kotobaya.android.shell.api.review.AnswerTestSupport.remoteReview
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.shell.api.review.AnswerTestSupport.undo
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/**
 * SHELL-L3 acceptance `AnswerOutOfOrderTest` (SYNC.md 9.1-9.3, worked example 12.1): answers given through the local
 * API interleaved with reviews of the same card pulled from another device — earlier and later `reviewed_at`, earlier
 * and later HLCs, in every arrival order. The card must always equal a full replay of the replay set in
 * `(reviewed_at, hlc, id)` order; the handler never computes a schedule, so this holds iff the mint goes through the
 * op-apply path's fold / full-replay decision.
 *
 * The oracle is a second replica that receives the same ops (the device's own, from its outbox, and the pulled
 * ones) one by one in replay order — each an incremental fold, i.e. the replay itself — and a third that receives
 * them in the reverse order (every one a full replay).
 */
class AnswerOutOfOrderTest {
    private sealed interface Step {
        /** `answerReview` on the device at device time [at]; [reviewedAt] null = the default (the device's now). */
        class Local(val id: String, val rating: Int, val at: Long, val reviewedAt: Long? = null) : Step

        /** Ops of the other device arrive by pull at device time [at]. */
        class Pull(val ops: List<JsonObject>, val at: Long) : Step
    }

    private val end = T0 + 600_000

    private fun replayKey(op: JsonObject): String {
        val p = ParityJson.obj(op.members["payload"])
        return "%020d %s %s".format(ParityJson.long(p.members["reviewed_at"]), ParityJson.str(op.members["hlc"]), ParityJson.str(p.members["id"]))
    }

    private fun run(steps: List<Step>): ParityDevice {
        val d = device(X)
        for (s in steps) when (s) {
            is Step.Local -> {
                val extra = s.reviewedAt?.let { """"reviewed_at":"${iso(it)}"""" }.orEmpty()
                assertEquals(200, d.answer(s.id, C1, rating = s.rating, at = s.at, extra = extra).status)
            }
            is Step.Pull -> {
                d.clock.now = s.at
                d.pull(s.ops, "pull")
            }
        }
        return d
    }

    /** A replica that got [ops] one at a time in the order [order] gives. */
    private fun replica(id: String, ops: List<JsonObject>, order: (List<JsonObject>) -> List<JsonObject>): ParityDevice {
        val z = device(id)
        z.clock.now = end
        order(ops).forEach { z.pull(listOf(it), "oracle") }
        return z
    }

    private fun assertEqualsAFullReplay(name: String, steps: List<Step>): ParityDevice {
        val d = run(steps)
        val all = d.minted() + steps.filterIsInstance<Step.Pull>().flatMap { it.ops }
        val inOrder = replica(Y, all) { it.sortedBy(::replayKey) }
        val reversed = replica(Z, all) { it.sortedBy(::replayKey).reversed() }
        assertEquals(inOrder.reviews(), d.reviews(), "$name: review_log")
        assertEquals(inOrder.cards(), d.cards(), "$name: card equals the replay in timestamp order")
        assertEquals(inOrder.cards(), reversed.cards(), "$name: and the replay of the reverse arrival")
        return d
    }

    // ---- SYNC.md 12.1: two devices review the same card offline -----------------------------------------------------

    private val t = T0 + 60_000
    private val r1 = reviewId(1) // this device, T+10, good
    private val r2 = reviewId(2) // this device, T+30, good
    private val r3 = reviewId(3) // B, T+20, again
    private val r4 = reviewId(4) // B, T+25, drill
    private val bReview = remoteReview(r3, C1, reviewedAt = t + 20, hlcWall = t + 20, rating = 1, type = "scheduled")
    private val bDrill = remoteReview(r4, C1, reviewedAt = t + 25, hlcWall = t + 25, rating = 3, type = "drill")

    @Test
    fun workedExample12_1_inEveryArrivalOrder() {
        val orders = mapOf(
            "this device first, then B's reviews (B@20 sorts before the cached last review)" to
                listOf(Step.Local(r1, 3, t + 10), Step.Local(r2, 3, t + 30), Step.Pull(listOf(bReview, bDrill), t + 40)),
            "B's reviews between the two answers" to
                listOf(Step.Local(r1, 3, t + 10), Step.Pull(listOf(bReview, bDrill), t + 26), Step.Local(r2, 3, t + 30)),
            "B's reviews first, then late answers dated before them" to
                listOf(Step.Pull(listOf(bReview, bDrill), t + 40), Step.Local(r1, 3, t + 41, reviewedAt = t + 10), Step.Local(r2, 3, t + 42, reviewedAt = t + 30)),
            "the later answer submitted before the earlier one" to
                listOf(Step.Local(r2, 3, t + 41, reviewedAt = t + 30), Step.Pull(listOf(bDrill, bReview), t + 42), Step.Local(r1, 3, t + 43, reviewedAt = t + 10)),
        )
        for ((name, steps) in orders) {
            val d = assertEqualsAFullReplay(name, steps)
            assertEquals(listOf(r1, r2, r3, r4), d.reviews().map { ParityJson.str(it["id"]) }, "$name: nothing dropped")
            val card = d.card(C1)
            // the replay set is r1, r3, r2 (the drill is logged only): three rated reviews, the last one r2
            assertEquals(JsonNumber(3.0), card["reps"], name)
            assertEquals(JsonString(r2), card["last_review_id"], name)
            assertEquals(JsonNumber((t + 30).toDouble()), card["last_review_at"], name)
            assertEquals(JsonString("drill"), d.reviews().single { it["id"] == JsonString(r4) }["review_type"], name)
        }
    }

    // ---- HLC order and answer-time order disagree ---------------------------------------------------------------------

    private val s = T0 + 120_000

    /** Answered before this device's answer, but minted with a later HLC (B's clock ran ahead). */
    private val early = remoteReview(reviewId(10), C1, reviewedAt = s + 10_000, hlcWall = s + 90_000, rating = 1, type = "learning")

    /** Answered after this device's answers, but with an earlier HLC (a late submission of a slow clock). */
    private val late = remoteReview(reviewId(11), C1, reviewedAt = s + 80_000, hlcWall = s + 20_000, rating = 4, type = "learning")

    /** The same `reviewed_at` as this device's first answer and an earlier HLC: the HLC breaks the tie, B's sorts first. */
    private val tie = remoteReview(reviewId(12), C1, reviewedAt = s + 50_000, hlcWall = s + 15_000, rating = 2, type = "learning")

    @Test
    fun pulledReviewsWithEarlierAndLaterHlcsThanTheirAnswerTimes() {
        val l1 = Step.Local(reviewId(1), 3, s + 50_000)
        val l2 = Step.Local(reviewId(2), 3, s + 70_000)
        val orders = mapOf(
            "pulled early, answers, pulled late" to listOf(Step.Pull(listOf(early), s + 40_000), l1, l2, Step.Pull(listOf(late, tie), s + 95_000)),
            "answers, then everything pulled" to listOf(l1, l2, Step.Pull(listOf(late, early, tie), s + 95_000)),
            "everything pulled, then the answers (dated before the cached last review)" to
                listOf(Step.Pull(listOf(tie, early, late), s + 45_000), l1, l2),
            "pulled late first, an answer, pulled early, an answer, the tie" to
                listOf(Step.Pull(listOf(late), s + 45_000), l1, Step.Pull(listOf(early), s + 60_000), l2, Step.Pull(listOf(tie), s + 95_000)),
        )
        val cards = orders.map { (name, steps) ->
            val d = assertEqualsAFullReplay(name, steps)
            assertEquals(JsonNumber(5.0), d.card(C1)["reps"], name)
            assertEquals(late.members["entity_id"], d.card(C1)["last_review_id"], "$name: the last review by answer time is B's late one")
            d.card(C1).filterKeys { it !in setOf("last_review_id") }
        }
        // the set of rated reviews and their answer times are the same in every order here, so the schedule is too
        cards.drop(1).forEach { assertEquals(cards[0], it) }
    }

    /** A device whose HLC was pushed far ahead by a pulled op still logs its answers at its own wall time. */
    @Test
    fun anAnswerAfterObservingAFutureHlcKeepsItsOwnReviewedAt() {
        val d = assertEqualsAFullReplay("future hlc observed", listOf(Step.Pull(listOf(early), s + 1_000), Step.Local(reviewId(1), 3, s + 50_000)))
        val own = d.reviews().single { it["id"] == JsonString(reviewId(1)) }
        assertEquals(JsonNumber((s + 50_000).toDouble()), own["reviewed_at"])
        assertEquals(true, ParityJson.str(own["hlc"]) > ParityJson.str(early.members["hlc"]), "its HLC is above the observed one")
    }

    // ---- undo out of order ------------------------------------------------------------------------------------------

    private fun undoOp(reviewId: String, wall: Long, n: Int): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "op_id" to JsonString("0193b000-00fe-7000-8000-%012x".format(n)),
            "op_type" to JsonString("review.undone"),
            "op_version" to JsonNumber(1.0),
            "entity_type" to JsonString("review_log"),
            "entity_id" to JsonString(reviewId),
            "device_id" to JsonString(B),
            "hlc" to JsonString(hlc(wall, B)),
            "payload" to JsonObject(linkedMapOf<String, JsonValue>("review_id" to JsonString(reviewId))),
        ),
    )

    /** SYNC.md 7.9 rule 3 through the API: the earliest undo is recorded whichever arrives first; the card ignores the review. */
    @Test
    fun aLocalUndoAndAPulledUndoOfTheSameReviewKeepTheEarliest() {
        val earlier = undoOp(reviewId(1), s + 60_000, 1)

        // the device undoes at s+70 s, then B's undo minted at s+60 s arrives
        val a = device(X)
        a.answer(reviewId(1), C1, at = s + 50_000)
        a.answer(reviewId(2), C1, rating = 4, at = s + 55_000)
        assertEquals(200, a.undo(reviewId(1), at = s + 70_000).status)
        a.clock.now = s + 80_000
        a.pull(listOf(earlier), "B's earlier undo")

        // B's undo arrives first; the device's own undo request then answers 200 and mints nothing
        val b = device(X)
        b.answer(reviewId(1), C1, at = s + 50_000)
        b.answer(reviewId(2), C1, rating = 4, at = s + 55_000)
        b.clock.now = s + 65_000
        b.pull(listOf(earlier), "B's earlier undo")
        val mintedBefore = b.minted().size
        assertEquals(200, b.undo(reviewId(1), at = s + 70_000).status)
        assertEquals(mintedBefore, b.minted().size)

        for (d in listOf(a, b)) {
            val row = d.reviews().single { it["id"] == JsonString(reviewId(1)) }
            assertEquals(earlier.members["hlc"], row["undone_hlc"])
            assertEquals(JsonNumber((s + 60_000).toDouble()), row["undone_at"])
            assertEquals(JsonNumber(1.0), d.card(C1)["reps"], "only the second answer is in the replay set")
            assertEquals(JsonString(reviewId(2)), d.card(C1)["last_review_id"])
        }
        assertEquals(a.cards(), b.cards())
        assertEquals(JsonNull, a.reviews().single { it["id"] == JsonString(reviewId(2)) }["undone_at"])
    }
}
