package kotobaya.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `presentLoop` as the web's core adapter calls it: exported DTOs, wire strings, ms as JS numbers. */
class LoopPresenterJsTest {
    private val dayEnd = 1704135600000.0
    private fun loop(phase: String, l: Int, la: Int, rv: Int, n: Int) = JsLoopInput(phase, JsUntilStopParts(l, la, rv, n))
    private fun JsLoopPresentation.flat() = listOf(screen, parts.learning, parts.later, parts.review, parts.new, untilStop)

    @Test
    fun p3RoundTrips() {
        // P3: state REVIEWING (0, 5, 30, 30); Again on a review, then a new card Good; a REVIEW card held.
        val pending = arrayOf(
            JsPendingAnswer("review", 1, "relearning", 1704125400000.0),
            JsPendingAnswer("new", 3, "learning", 1704125400000.0),
        )
        assertEquals(listOf("card", 0, 7, 29, 29, 58), presentLoop(loop("reviewing", 0, 5, 30, 30), pending, true, "review", dayEnd).flat())
    }

    @Test
    fun p7UnknownOutcomeWithNullsIsSettling() {
        val got = presentLoop(loop("reviewing", 0, 0, 1, 0), arrayOf(JsPendingAnswer("review", 3, null, null)), false, null, dayEnd)
        assertEquals(listOf("settling", 0, 0, 0, 0, 0), got.flat())
    }

    @Test
    fun p9HeldLearningCardWithAStaleStopState() {
        assertEquals(listOf("card", 1, 0, 0, 0, 1), presentLoop(loop("goal_reached", 0, 0, 0, 0), emptyArray(), true, "learning", dayEnd).flat())
    }

    @Test
    fun unknownWireValuesAndBadInstantsThrow() {
        assertFailsWith<IllegalArgumentException> { presentLoop(loop("pending", 0, 0, 0, 0), emptyArray(), false, null, 0.0) }
        assertFailsWith<IllegalArgumentException> { presentLoop(loop("empty", 0, 0, 0, 0), emptyArray(), true, "kanji", 0.0) }
        assertFailsWith<IllegalArgumentException> {
            presentLoop(loop("empty", 0, 0, 0, 0), arrayOf(JsPendingAnswer("review", 3, "suspended", null)), false, null, 0.0)
        }
        assertFailsWith<IllegalArgumentException> { presentLoop(loop("empty", 0, 0, 0, 0), emptyArray(), false, null, 1.5) }
        assertEquals("empty", presentLoop(loop("empty", 0, 0, 0, 0), emptyArray(), false, null, 0.0).screen)
    }
}
