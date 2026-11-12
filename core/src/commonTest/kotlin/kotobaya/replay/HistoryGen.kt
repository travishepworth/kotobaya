package kotobaya.replay

import kotobaya.contracts.HlcString
import kotobaya.contracts.fsrs.ManualAction
import kotobaya.contracts.fsrs.Rating
import kotobaya.contracts.fsrs.ReviewType
import kotobaya.contracts.fsrs.SchedulerParams
import kotobaya.contracts.replay.ReplayReview

/** One thing a replica applies for the card. */
internal sealed class Event {
    /** `review.logged` (a duplicate delivery has an id already logged, possibly with other content). */
    data class Logged(val row: ReplayReview) : Event()

    /** `review.undone` of [id] with op clock [hlc] (wall = [wall]). */
    data class Undo(val id: String, val hlc: HlcString, val wall: Long) : Event()

    /** The effective preset changed; [eager] = 9.2 replays at once, else lazily via weights_version. */
    data class Params(val params: SchedulerParams, val eager: Boolean) : Event()
}

/**
 * A random history of one card: [rows] are the first-delivered `review_log` rows; [events] in
 * generation order (every Undo and duplicate Logged comes after the Logged of its row).
 */
internal class History(val rows: List<ReplayReview>, val events: List<Event>, val initial: SchedulerParams) {
    val undoneIds: Set<String> = events.filterIsInstance<Event.Undo>().map { it.id }.toSet()
    val finalParams: SchedulerParams = events.filterIsInstance<Event.Params>().lastOrNull()?.params ?: initial

    /** Rows of the final replica state (undone rows marked), for the reference full replay. */
    fun finalRows(): List<ReplayReview> = rows.map { if (it.id in undoneIds) it.copy(undoneHlc = "x") else it }

    /** A random arrival order that keeps each Undo and duplicate after its row's first Logged. */
    fun arrival(rng: Rng): List<Event> {
        val first = rows.associate { it.id to Event.Logged(it) as Event }
        val dependents = events.filter { it !in first.values }
        val order = rng.shuffled(first.values.toList()).toMutableList()
        for (e in rng.shuffled(dependents)) {
            val after = when (e) {
                is Event.Logged -> order.indexOf(first.getValue(e.row.id))
                is Event.Undo -> order.indexOf(first.getValue(e.id))
                is Event.Params -> -1
            }
            val pos = after + 1 + rng.int(order.size - after)
            order.add(pos, e)
        }
        return order
    }
}

internal fun genHistory(rng: Rng, index: Int, maxRows: Int = 20): History {
    val n = 1 + rng.int(maxRows)
    var t = T0 + rng.long(0, 30 * DAY)
    val rows = ArrayList<ReplayReview>()
    val events = ArrayList<Event>()
    for (i in 0 until n) {
        t += when (rng.int(10)) {
            0 -> 0L
            1 -> rng.long(1, 60_000)
            2, 3, 4 -> rng.long(60_000, DAY)
            else -> rng.long(DAY, 40 * DAY)
        }
        // a review answered earlier than the previous one was generated (other device, late push)
        val at = if (rng.chance(12)) t - rng.long(0, 3 * DAY) else t
        val device = if (rng.chance(50)) DEV_A else DEV_B
        // hlc mostly tracks reviewed_at; sometimes skewed (re-mint, clock ahead), sometimes equal ms
        val hlcMs = if (rng.chance(15)) at + rng.long(-7_200_000, 7_200_000) else at
        val h = hlc(hlcMs, rng.int(3), device)
        val id = uuid(index and 0xffff, rng.nextLong())
        val roll = rng.int(100)
        val row = when {
            roll < 40 -> rated(id, at, rng.pick(Rating.entries), ReviewType.SCHEDULED, h, rng.pick(listOf(540, -300, null)))
            roll < 60 -> rated(id, at, rng.pick(Rating.entries), ReviewType.LEARNING, h, 540)
            roll < 70 -> rated(id, at, rng.pick(Rating.entries), ReviewType.DRILL, h)
            roll < 75 -> rated(id, at, rng.pick(Rating.entries), ReviewType.CRAM, h)
            roll < 93 -> manual(id, at, ManualAction.SET_DUE, at + rng.long(-2 * DAY, 60 * DAY), h)
            else -> manual(id, at, ManualAction.RESET, null, h)
        }
        rows.add(row)
        events.add(Event.Logged(row))
        if (rng.chance(12)) {
            repeat(if (rng.chance(30)) 2 else 1) {
                val ms = at + rng.long(0, DAY)
                events.add(Event.Undo(id, hlc(ms, rng.int(3), rng.pick(listOf(DEV_A, DEV_B))), ms))
            }
        }
        if (rng.chance(8)) {
            // same id, different content: the first insert wins (7.9 rule 1)
            events.add(Event.Logged(row.copy(rating = Rating.AGAIN, reviewType = ReviewType.SCHEDULED, manualAction = null, manualDueAt = null, reviewedAt = at + 1)))
        }
    }
    when (rng.int(10)) {
        in 0..3 -> events.add(Event.Params(P1, eager = false))
        in 4..6 -> events.add(Event.Params(P0B, eager = true))
        else -> Unit
    }
    return History(rows, events, P0)
}
