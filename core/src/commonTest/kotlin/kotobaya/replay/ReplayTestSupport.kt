package kotobaya.replay

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.CardCache
import kotobaya.contracts.replay.ReplayDecision
import kotobaya.contracts.replay.ReplayReview
import kotobaya.fsrs.FsrsScheduler

internal const val DAY: Long = Reserved.DAY_MS
internal const val T0: Long = 1_704_099_600_000L
internal const val CARD: Uuid = "0193a1b2-0002-7000-8000-000000000001"
internal const val DEV_A: Uuid = "01931f3e-0000-7000-8000-0000000000a1"
internal const val DEV_B: Uuid = "01931f3e-0000-7000-8000-0000000000b2"

/** `review_log.import_source` of imported jpdb history (SYNC.md 9.5). */
internal const val JPDB: String = DefaultReplayEngine.JPDB_IMPORT_SOURCE

/**
 * An imported jpdb review as the history import stores it: server device, `hlc` = replay_hlc built from
 * the historical time (`<ms>-<counter>-<server device id>`), `utc_offset_min` NULL, `import_source` jpdb.
 */
internal fun imported(
    id: Uuid,
    at: InstantMs,
    rating: Rating,
    counter: Int = 0,
    type: ReviewType = ReviewType.SCHEDULED,
): ReplayReview = rated(id, at, rating, type, hlc(at, counter, Reserved.SERVER_DEVICE_ID), offset = null).copy(importSource = JPDB)

/** Deterministic PRNG for the property tests (SplitMix64 stream; identical on jvm and js). */
internal class Rng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        val s = state
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        return FsrsScheduler.splitMix64(s)
    }

    /** Uniform in `0 until n` (n > 0); the tiny modulo bias is irrelevant here. */
    fun int(n: Int): Int = ((nextLong() ushr 1) % n.toLong()).toInt()

    fun long(lo: Long, hiExclusive: Long): Long = lo + ((nextLong() ushr 1) % (hiExclusive - lo))

    fun chance(percent: Int): Boolean = int(100) < percent

    fun <T> pick(xs: List<T>): T = xs[int(xs.size)]

    fun <T> shuffled(xs: List<T>): List<T> {
        val a = xs.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = int(i + 1)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a
    }
}

internal fun hex(v: Long, digits: Int): String = v.toULong().toString(16).padStart(16, '0').takeLast(digits)

internal fun hlc(ms: Long, counter: Int = 0, device: Uuid = DEV_A): HlcString =
    hex(ms, 12) + "-" + hex(counter.toLong(), 4) + "-" + device

/** A canonical lowercase UUID whose low 64 bits are [low] (so fuzz seeds vary). */
internal fun uuid(high: Int, low: Long): Uuid {
    val h = hex(low, 16)
    return "0193a1b2-" + hex(high.toLong(), 4) + "-7000-" + h.substring(0, 4) + "-" + h.substring(4)
}

internal fun rated(
    id: Uuid,
    at: InstantMs,
    rating: Rating,
    type: ReviewType = ReviewType.SCHEDULED,
    hlc: HlcString = hlc(at),
    offset: Int? = 540,
    undone: HlcString? = null,
): ReplayReview = ReplayReview(id, CARD, hlc, at, offset, type, rating, null, null, undone)

internal fun manual(
    id: Uuid,
    at: InstantMs,
    action: ManualAction,
    dueAt: InstantMs? = null,
    hlc: HlcString = hlc(at),
): ReplayReview = ReplayReview(id, CARD, hlc, at, null, ReviewType.MANUAL, null, action, dueAt, null)

/** The scheduler block of fixtures/fsrs/vectors.json (fuzz off). */
internal val VECTOR_PARAMS: SchedulerParams = SchedulerParams(
    weights = FsrsScheduler.defaultWeights,
    desiredRetention = 0.9,
    maximumIntervalDays = 36500,
    learningStepsSeconds = listOf(60, 600),
    relearningStepsSeconds = listOf(600),
    fuzz = false,
    easyDays = List(7) { 1.0 },
    weightsVersion = 0,
)

/** Fuzz on, uneven easy days: the default preset as a user might have it. */
internal val P0: SchedulerParams = VECTOR_PARAMS.copy(fuzz = true, easyDays = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 0.5, 0.0))

/** Optimizer published new weights (weights_version bumps): handled lazily by the mismatch. */
internal val P1: SchedulerParams = P0.copy(
    weights = FsrsScheduler.defaultWeights.mapIndexed { i, w ->
        when (i) {
            0 -> 0.3
            8 -> 1.5
            20 -> 0.2
            else -> w
        }
    },
    weightsVersion = 1,
)

/** `fsrs_params.updated` of desired retention and steps (same weights_version): handled eagerly. */
internal val P0B: SchedulerParams = P0.copy(desiredRetention = 0.85, learningStepsSeconds = listOf(300), relearningStepsSeconds = emptyList())

/**
 * A replica's `review_log` table and `card` cache for one card, driven only through the contract
 * (`ReviewLogRules`, `decide`, `fold`, `replay`) the way the op-apply path does (SYNC.md 7.9, 9.2, 9.3).
 */
internal class TableReplica(
    private val engine: DefaultReplayEngine = DefaultReplayEngine,
    initial: SchedulerParams,
) {
    private class Stored(var row: ReplayReview, var undoneAt: Long?)

    private val table = LinkedHashMap<Uuid, Stored>()
    var params: SchedulerParams = initial
        private set
    private var stored: CardCache = engine.replay(emptyList(), initial)
    val decisions: MutableMap<ReplayDecision, Int> = mutableMapOf()

    fun rows(): List<ReplayReview> = table.values.map { it.row }

    fun logged(r: ReplayReview) {
        if (!DefaultReviewLogRules.shouldInsert(table.containsKey(r.id))) return
        table[r.id] = Stored(r, null)
        val lastFolded = stored.lastReviewId?.let { table[it]?.row?.key }
        val d = engine.decide(stored, lastFolded, r, params)
        decisions[d] = (decisions[d] ?: 0) + 1
        stored = when (d) {
            ReplayDecision.NONE -> stored
            ReplayDecision.FOLD -> engine.fold(stored, r, params)
            ReplayDecision.FULL_REPLAY -> engine.replay(rows(), params)
        }
    }

    /** Returns the outcome's replayTarget (false for an unknown id). */
    fun undone(id: Uuid, opHlc: HlcString, wall: Long): Boolean {
        val s = table[id] ?: return false // device: unknown id → no row, no error
        val o = DefaultReviewLogRules.undo(s.row.undoneHlc, s.undoneAt, opHlc, wall, s.row.reviewType.feedsSchedule)
        if (o.apply) {
            s.row = s.row.copy(undoneHlc = o.undoneHlc)
            s.undoneAt = o.undoneAt
        }
        if (o.replayTarget) stored = engine.replay(rows(), params)
        return o.replayTarget
    }

    /** [eager]: a register change that 9.2 replays at once; otherwise the weights_version mismatch marks the cache. */
    fun paramsChanged(p: SchedulerParams, eager: Boolean) {
        params = p
        if (eager) stored = engine.replay(rows(), params)
    }

    /** The cache as a reader sees it: a stale cache is replayed before it is read (9.3). */
    fun read(): CardCache {
        if (stored.weightsVersion != params.weightsVersion) stored = engine.replay(rows(), params)
        return stored
    }
}
