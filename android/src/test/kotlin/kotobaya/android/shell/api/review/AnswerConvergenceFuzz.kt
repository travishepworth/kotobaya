package kotobaya.android.shell.api.review

import kotlin.random.Random
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
import kotobaya.android.shell.api.review.AnswerTestSupport.Y
import kotobaya.android.shell.api.review.AnswerTestSupport.Z
import kotobaya.android.shell.api.review.AnswerTestSupport.answer
import kotobaya.android.shell.api.review.AnswerTestSupport.cards
import kotobaya.android.shell.api.review.AnswerTestSupport.device
import kotobaya.android.shell.api.review.AnswerTestSupport.iso
import kotobaya.android.shell.api.review.AnswerTestSupport.reviewId
import kotobaya.android.shell.api.review.AnswerTestSupport.reviews
import kotobaya.android.shell.api.review.AnswerTestSupport.undo
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonObject

/**
 * SHELL-L3 acceptance `AnswerConvergenceFuzz` (SYNC.md 11; SHELL.md 8.4 (c)): two phones answer and undo through
 * the local API (`answerReview` / `undoReview` on real `SqlDelightDeviceStore`s, the production minter, applier and
 * pull engine), push to a server log and pull from it at random moments, with retried requests, late submissions,
 * equal answer times and clocks that disagree by 20 s. At the end both phones and an observer that only ever pulled
 * the log hold identical `card` and `review_log` tables, every accepted answer is in the log exactly once, and every
 * op validates against the op schema.
 *
 * The core's fuzz harness (`DeviceConvergenceFuzzTest`) lives in core's test sources and drives op lists, not HTTP
 * handlers; this is its shape (random interleavings of per-device streams against one log, an observer as the
 * oracle) over the shell's handlers. A failure names its seed: run [runSeed] with it to reproduce.
 */
class AnswerConvergenceFuzz {
    private class Phone(val d: ParityDevice, val skewMs: Long) {
        val pushed = HashSet<String>()
        var cursor = 0
    }

    @Test
    fun randomAnswerUndoPullSequencesOnTwoStoresConverge() {
        for (seed in 0 until SEEDS) {
            try {
                runSeed(seed)
            } catch (e: Throwable) {
                throw AssertionError("AnswerConvergenceFuzz failed with seed $seed: ${e.message}", e)
            }
        }
    }

    private fun opId(op: JsonObject): String = ParityJson.str(op.members["op_id"])

    private fun runSeed(seed: Int) {
        val rnd = Random(seed)
        val x = Phone(device(X), skewMs = 7_000)
        val y = Phone(device(Y), skewMs = -13_000)
        val observer = Phone(device(Z), skewMs = 0)
        val phones = listOf(x, y)
        val log = ArrayList<JsonObject>()
        var now = T0 + 60_000
        var nextId = 0
        /** Review ids whose answer was accepted (a 200 that was not a retry), in order. */
        val accepted = ArrayList<String>()
        var undoOps = 0

        fun push(p: Phone) {
            val fresh = p.d.minted().filter { opId(it) !in p.pushed }
            log += fresh
            p.pushed += fresh.map(::opId)
        }

        fun pull(p: Phone) {
            val fresh = log.drop(p.cursor)
            if (fresh.isEmpty()) return
            p.d.clock.now = now + p.skewMs
            // the whole log, the phone's own ops included (a real pull returns them; applying them again changes nothing)
            p.d.pull(fresh, "pull")
            p.cursor = log.size
        }

        repeat(STEPS) {
            now += rnd.nextLong(1, 90_000)
            val p = phones[rnd.nextInt(2)]
            val clock = now + p.skewMs
            val known = p.d.reviews()
            when (rnd.nextInt(100)) {
                in 0 until 50 -> {
                    val id = reviewId(++nextId)
                    val card = if (rnd.nextBoolean()) C1 else C2
                    val mode = when (rnd.nextInt(8)) {
                        0 -> "drill"
                        1 -> "cram"
                        else -> "schedule"
                    }
                    val reviewedAt = when (rnd.nextInt(10)) {
                        0, 1 -> clock - rnd.nextLong(0, 2 * HOUR) // a late submission
                        2 -> known.randomOrNull(rnd)?.let { ParityJson.long(it["reviewed_at"]) }?.takeIf { it <= clock } // the same instant as another review
                        else -> null
                    }
                    val extra = """"mode":"$mode"""" + (reviewedAt?.let { ""","reviewed_at":"${iso(it)}"""" } ?: "")
                    val before = p.d.minted().size
                    val res = p.d.answer(id, card, rating = rnd.nextInt(1, 5), at = clock, extra = extra)
                    assertEquals(200, res.status, "answer $id: ${res.body}")
                    assertEquals(before + 1, p.d.minted().size, "answer $id minted one op")
                    accepted += id
                }
                in 50 until 62 -> known.randomOrNull(rnd)?.let { row ->
                    // a retry of an answer this phone already holds (its own or a pulled one), with a different body
                    val before = p.d.minted().size
                    val res = p.d.answer(ParityJson.str(row["id"]), if (rnd.nextBoolean()) C1 else C2, rating = rnd.nextInt(1, 5), at = clock)
                    assertEquals(200, res.status)
                    assertEquals(before, p.d.minted().size, "a retry mints nothing")
                }
                in 62 until 76 -> known.randomOrNull(rnd)?.let { row ->
                    val wasUndone = (row["undone_at"] ?: JsonNull) != JsonNull
                    val before = p.d.minted().size
                    val res = p.d.undo(ParityJson.str(row["id"]), at = clock)
                    assertEquals(200, res.status)
                    assertEquals(before + if (wasUndone) 0 else 1, p.d.minted().size, "an undo mints once per phone and review")
                    if (!wasUndone) undoOps++
                }
                in 76 until 88 -> push(p)
                else -> pull(p)
            }
        }

        // everyone syncs: push everything, then pull the whole log
        now += 60_000
        phones.forEach(::push)
        (phones + observer).forEach(::pull)

        assertTrue(log.all(::isValidOp), "every minted op validates against op.schema.json")
        val logged = log.filter { ParityJson.str(it.members["op_type"]) == "review.logged" }.map { ParityJson.str(it.members["entity_id"]) }
        assertEquals(accepted.sorted(), logged.sorted(), "every accepted answer is in the log exactly once")
        assertEquals(undoOps, log.size - logged.size)

        val reviews = observer.d.reviews()
        assertEquals(accepted.sorted(), reviews.map { ParityJson.str(it["id"]) }, "no review lost, none doubled")
        for (p in phones) {
            assertEquals(reviews, p.d.reviews(), "review_log of ${p.d.deviceId}")
            assertEquals(observer.d.cards(), p.d.cards(), "card of ${p.d.deviceId}")
        }
    }

    private companion object {
        const val SEEDS = 120
        const val STEPS = 80
    }
}
