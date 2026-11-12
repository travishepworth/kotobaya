package kotobaya.replay

import kotobaya.contracts.fsrs.CardState
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.Fnv1a64
import kotobaya.fsrs.FsrsScheduler
import kotobaya.fsrs.FsrsVectors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T203 acceptance: the fixtures/fsrs vectors replayed through the engine, and a jvm/js digest of
 * replay outputs (the same hard-coded constant is checked on both platforms).
 */
class ReplayGoldenTest {
    private val e = DefaultReplayEngine

    private class Vec(val rating: Rating, val at: Long, val state: CardState, val step: Int?, val s: Double, val d: Double, val due: Long)

    private fun vectors(): List<List<Vec>> = FsrsVectors.sequences.map { seq ->
        seq.split(';').map { r ->
            val f = r.split(',')
            Vec(
                Rating.entries.first { it.value == f[0].toInt() },
                f[1].toLong(),
                when (f[2]) {
                    "L" -> CardState.LEARNING
                    "R" -> CardState.REVIEW
                    else -> CardState.RELEARNING
                },
                if (f[3] == "-") null else f[3].toInt(),
                f[4].toDouble(),
                f[5].toDouble(),
                f[6].toLong(),
            )
        }
    }

    /** Rows of a vector sequence; hlc increases with the index so equal `reviewed_at` keep file order. */
    private fun rows(si: Int, seq: List<Vec>): List<ReplayReview> = seq.mapIndexed { i, v ->
        rated(
            id = uuid(si, FsrsScheduler.splitMix64(si * 1000L + i)),
            at = v.at,
            rating = v.rating,
            type = if (i == 0) ReviewType.LEARNING else ReviewType.SCHEDULED,
            hlc = hlc(T0 + i, 0, DEV_A),
        )
    }

    @Test
    fun goldenVectorsThroughReplay() {
        var count = 0
        val rng = Rng(0x7203_0004L)
        for ((si, seq) in vectors().withIndex()) {
            val rows = rows(si, seq)
            // incremental, one row at a time, checking every vector state
            val replica = TableReplica(initial = VECTOR_PARAMS)
            for ((i, v) in seq.withIndex()) {
                replica.logged(rows[i])
                val c = replica.read().schedule
                val where = "seq $si review $i"
                assertEquals(v.state, c.state, where)
                assertEquals(v.step, c.step, where)
                assertEquals(v.due, c.dueAt, where)
                assertTrue(abs(v.s - c.stability!!) <= 1e-6, "$where stability")
                assertTrue(abs(v.d - c.difficulty!!) <= 1e-6, "$where difficulty")
                count++
            }
            // full replay of the shuffled rows gives the same cache
            assertEquals(replica.read(), e.replay(rng.shuffled(rows), VECTOR_PARAMS), "seq $si")
            assertEquals(rows.last().id, replica.read().lastReviewId)
        }
        assertEquals(3546, count)
    }

    /**
     * Cross-platform bit identity (SYNC.md 11.3): digest of every cache of the vector replays (fuzz
     * off and on) and of 200 random histories (manual rows, undos, drills, params changes). Computed
     * once on jvm; js must give the same constant.
     */
    @Test
    fun replayDigestIsIdenticalOnJvmAndJs() {
        val h = Fnv1a64()
        fun add(c: CardCache) {
            val s = c.schedule
            h.addInt(s.state.ordinal).addInt(s.step ?: -1)
            h.addDouble(s.stability ?: -1.0).addDouble(s.difficulty ?: -1.0)
            h.addLong(s.dueAt ?: -1).addInt(s.intervalDays ?: -1).addLong(s.lastReviewAt ?: -1)
            h.addInt(s.reps).addInt(s.lapses).addInt(c.weightsVersion)
            for (ch in c.lastReviewId ?: "-") h.addInt(ch.code)
        }
        for ((si, seq) in vectors().withIndex()) {
            val rows = rows(si, seq)
            add(e.replay(rows, VECTOR_PARAMS))
            add(e.replay(rows, P0))
            add(e.replay(rows, P1))
        }
        val rng = Rng(0x7203_0005L)
        repeat(200) { case ->
            val hist = genHistory(rng, case)
            add(e.replay(hist.finalRows(), hist.finalParams))
            add(e.replay(hist.rows, P0B))
        }
        assertEquals(EXPECTED_DIGEST, h.value, "digest 0x" + h.value.toULong().toString(16))
    }

    private companion object {
        const val EXPECTED_DIGEST: Long = -5368876589278887028L // 0xb57deb30051f038c
    }
}
