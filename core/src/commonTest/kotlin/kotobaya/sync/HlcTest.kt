package kotobaya.sync

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.HlcParts
import kotobaya.contracts.sync.HlcState
import kotobaya.contracts.sync.RemintItem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNC.md 3 (format, mint, observe, uniqueness, guards) and 10.4 step 5 (re-mint shift). */
class HlcTest {
    private val h = DefaultHlcRules
    private val dA = "01931f3e-0000-7000-8000-0000000000a1"
    private val dB = "01931f3e-0000-7000-8000-0000000000b2"

    @Test
    fun parseAndFormatKdocExample() {
        val s = "0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1"
        val p = h.parse(s)
        assertEquals(HlcParts(1733584667604L, 1, dA), p)
        assertEquals(s, h.format(HlcParts(0x0193a1b2c3d4L, 1, dA)))
        assertEquals(1733584667604L, h.wall(s))
        assertEquals(HlcState(1733584667604L, 1), h.stateOf(s))
        assertEquals(HlcState.INITIAL, h.stateOf(null))
        assertEquals("000000000000-0000-$dA", h.format(HlcParts(0, 0, dA)))
        assertEquals("ffffffffffff-ffff-$dA", h.format(HlcParts(0xffffffffffffL, 0xffff, dA)))
    }

    @Test
    fun parseRejectsNonCanonicalForms() {
        val good = "0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1"
        val bad = listOf(
            "193a1b2c011-0000-01931f3e-0000-7000-8000-0000000000a1", // examples/invalid/op-short-hlc
            good.uppercase(),
            "0193A1B2C3D4-0001-01931f3e-0000-7000-8000-0000000000a1",
            "0193a1b2c3d4-0001-01931F3E-0000-7000-8000-0000000000a1",
            "0193a1b2c3d4_0001-01931f3e-0000-7000-8000-0000000000a1",
            "0193a1b2c3d4-0001-01931f3e00000-7000-8000-0000000000a1",
            "$good ",
            " $good",
            "0193a1b2c3d4-001-01931f3e-0000-7000-8000-0000000000a1x",
            "0193a1b2c3g4-0001-01931f3e-0000-7000-8000-0000000000a1",
            "",
        )
        for (b in bad) assertNull(h.parse(b), b)
        assertFailsWith<IllegalArgumentException> { h.wall(bad[0]) }
        assertFailsWith<IllegalArgumentException> { h.stateOf(bad[0]) }
        assertFailsWith<IllegalArgumentException> { h.observe(HlcState.INITIAL, bad[0]) }
    }

    @Test
    fun formatRejectsOutOfRangeParts() {
        assertFailsWith<IllegalArgumentException> { h.format(HlcParts(-1, 0, dA)) }
        assertFailsWith<IllegalArgumentException> { h.format(HlcParts(0x1000000000000L, 0, dA)) }
        assertFailsWith<IllegalArgumentException> { h.format(HlcParts(0, 0x10000, dA)) }
        assertFailsWith<IllegalArgumentException> { h.format(HlcParts(0, -1, dA)) }
        assertFailsWith<IllegalArgumentException> { h.format(HlcParts(0, 0, dA.uppercase())) }
        assertFailsWith<IllegalArgumentException> { h.format(HlcParts(0, 0, "not-a-uuid")) }
    }

    @Test
    fun compareIsByteWiseAndOrdersMsCounterDevice() {
        val a = h.format(HlcParts(1000, 5, dB))
        val b = h.format(HlcParts(1000, 6, dA))
        val c = h.format(HlcParts(1001, 0, dA))
        val d = h.format(HlcParts(1001, 0, dB))
        assertTrue(h.compare(a, b) < 0)
        assertTrue(h.compare(b, c) < 0)
        assertTrue(h.compare(c, d) < 0)
        assertTrue(h.compare(d, a) > 0)
        assertEquals(0, h.compare(c, c))
    }

    @Test
    fun mintKdocExamples() {
        assertEquals(HlcState(2000, 0), h.mint(HlcState(1000, 0), 2000, dA).state)
        assertEquals(h.format(HlcParts(2000, 0, dA)), h.mint(HlcState(1000, 0), 2000, dA).hlc)
        assertEquals(HlcState(1000, 6), h.mint(HlcState(1000, 5), 900, dA).state) // clock behind
        assertEquals(HlcState(1001, 0), h.mint(HlcState(1000, 0xffff), 1000, dA).state) // counter overflow
        assertEquals(HlcState(1000, 1), h.mint(HlcState(1000, 0), 1000, dA).state) // same ms
        assertEquals(HlcState(5, 0), h.mint(HlcState.INITIAL, 5, dA).state) // first HLC of a new replica
    }

    @Test
    fun mintRefusesToOverflowTheWallField() {
        assertFailsWith<IllegalStateException> { h.mint(HlcState(0xffffffffffffL, 0xffff), 0, dA) }
        assertFailsWith<IllegalStateException> { h.mint(HlcState.INITIAL, 0x1000000000000L, dA) }
        assertEquals(HlcState(0xffffffffffffL, 0), h.mint(HlcState(0xfffffffffffeL, 0xffff), 0, dA).state)
        assertFailsWith<IllegalArgumentException> { h.mint(HlcState.INITIAL, 5, "bad") }
    }

    @Test
    fun mintAtLandsJustAboveLastWhenBelowIt() {
        assertEquals(HlcState(1000, 4), h.mintAt(HlcState(1000, 3), 10, dA).state)
        assertEquals(HlcState(1000, 4), h.mintAt(HlcState(1000, 3), 1000, dA).state)
        assertEquals(HlcState(1500, 0), h.mintAt(HlcState(1000, 3), 1500, dA).state)
        assertEquals(HlcState(1001, 0), h.mintAt(HlcState(1000, 0xffff), 7, dA).state)
    }

    @Test
    fun observeNeverLowersAndIgnoresDevice() {
        val s = HlcState(1000, 5)
        assertEquals(s, h.observe(s, h.format(HlcParts(1000, 5, dB))))
        assertEquals(s, h.observe(s, h.format(HlcParts(1000, 4, dB))))
        assertEquals(s, h.observe(s, h.format(HlcParts(999, 0xffff, dB))))
        assertEquals(HlcState(1000, 6), h.observe(s, h.format(HlcParts(1000, 6, dB))))
        assertEquals(HlcState(2000, 0), h.observe(s, h.format(HlcParts(2000, 0, dB))))
        // after observing a future value, the next mint is above it even with a correct clock
        val after = h.observe(s, h.format(HlcParts(5000, 9, dB)))
        assertEquals(HlcState(5000, 10), h.mint(after, 3000, dA).state)
    }

    /** SYNC.md 3.3: per device, HLCs strictly increase in mint order, whatever the clock does. */
    @Test
    fun uniquenessUnderSkewProperty() {
        val rnd = Random(20261112)
        repeat(200) {
            var state = HlcState.INITIAL
            var last: String? = null
            var pt = 1_733_584_667_604L
            val seen = HashSet<String>()
            repeat(300) {
                pt += when (rnd.nextInt(6)) {
                    0 -> -rnd.nextLong(0, 100_000_000L) // clock set back
                    1 -> rnd.nextLong(0, 100_000_000L) // clock jumps ahead
                    2 -> 0L // many mints in one ms
                    else -> rnd.nextLong(0, 3L)
                }
                if (rnd.nextInt(10) == 0) {
                    val other = h.format(HlcParts(pt + rnd.nextLong(-1000, 1000), rnd.nextInt(0, 0x10000), dB))
                    state = h.observe(state, other)
                }
                val m = if (rnd.nextInt(8) == 0) h.mintAt(state, pt - 50_000, dA) else h.mint(state, pt, dA)
                assertTrue(m.state.ms > state.ms || (m.state.ms == state.ms && m.state.counter > state.counter))
                if (last != null) assertTrue(h.compare(last!!, m.hlc) < 0, "$last !< ${m.hlc}")
                assertTrue(seen.add(m.hlc))
                assertEquals(m.state, h.stateOf(m.hlc))
                state = m.state
                last = m.hlc
            }
        }
    }

    @Test
    fun counterRunsOverManyMintsInOneMillisecond() {
        var state = HlcState(1000, 0)
        repeat(0x10000) { state = h.mint(state, 1000, dA).state }
        assertEquals(HlcState(1001, 0), state)
    }

    @Test
    fun guardBoundaries() {
        val floor = Reserved.CLOCK_FLOOR_MS
        assertEquals(ClockGuardResult.OK, h.guard(floor, null))
        assertEquals(ClockGuardResult.G1_BEFORE_FLOOR, h.guard(floor - 1, null))
        assertEquals(ClockGuardResult.G1_BEFORE_FLOOR, h.guard(0, null))
        val last = h.format(HlcParts(floor + 10 * Reserved.DAY_MS, 3, dA))
        val wall = h.wall(last)
        assertEquals(ClockGuardResult.OK, h.guard(wall - Reserved.CLOCK_TOLERANCE_MS, last))
        assertEquals(ClockGuardResult.G2_BEHIND_LAST, h.guard(wall - Reserved.CLOCK_TOLERANCE_MS - 1, last))
        assertEquals(ClockGuardResult.OK, h.guard(wall + 5, last))
        // G1 is checked first
        val farLast = h.format(HlcParts(floor + 100 * Reserved.DAY_MS, 0, dA))
        assertEquals(ClockGuardResult.G1_BEFORE_FLOOR, h.guard(floor - 1, farLast))
    }

    /**
     * Scenario `clock-behind-remint`: A minted the card at `0193a1b2c3e8-0001`, its RTC reset to
     * 2000-01-01; a client without the guards keeps counting on `last` (the three rejected HLCs);
     * G1 would have refused all three. After the fix, re-mint mints at `now` and the shift reproduces
     * the scenario's `remint` block.
     */
    @Test
    fun scenarioClockBehindRemint() {
        val cardCreated = "0193a1b2c3e8-0001-$dA"
        var state = h.stateOf(cardCreated)
        val y2k = 946_684_800_000L
        val originalReviewedAt = listOf(y2k, 946_771_200_000L, 947_030_400_000L)
        val rejected = listOf(
            "0193a1b2c3e8-0002-$dA",
            "0193a1b2c3e8-0003-$dA",
            "0193a1b2c3e8-0004-$dA",
        )
        for ((i, pt) in originalReviewedAt.withIndex()) {
            assertEquals(ClockGuardResult.G1_BEFORE_FLOOR, h.guard(pt, h.format(HlcParts(state.ms, state.counter, dA))))
            val m = h.mint(state, pt, dA)
            assertEquals(rejected[i], m.hlc)
            state = m.state
        }
        // clock_behind needs no clock repair: hlc_last stays at the last rejected HLC.
        val now = 1_734_020_327_624L
        val offset = 786_903_467_624L
        assertEquals(ClockGuardResult.OK, h.guard(now, rejected.last()))
        val cardCreatedAt = h.wall(cardCreated)
        val shift = DefaultRemintRules.shift(
            originalReviewedAt.map { RemintItem(it, minOf(cardCreatedAt, now)) },
            offset,
            now,
        )
        assertEquals(offset, shift.delta)
        assertEquals(listOf(1_733_588_267_624L, 1_733_674_667_624L, 1_733_933_867_624L), shift.times)
        val reminted = shift.times.map { _ ->
            val m = h.mint(state, now, dA)
            state = m.state
            m.hlc
        }
        assertEquals(
            listOf("0193bbaa68c8-0000-$dA", "0193bbaa68c8-0001-$dA", "0193bbaa68c8-0002-$dA"),
            reminted,
        )
    }

    /** The three extra cases `tools/validate.py` checks for `remint_times`. */
    @Test
    fun remintShiftEdgeCases() {
        val r = DefaultRemintRules
        val y2k = 946_684_800_000L
        val t26 = 1_767_225_600_000L
        val a = r.shift(listOf(RemintItem(y2k, t26), RemintItem(y2k + 5, t26)), 0, t26 + 100)
        assertEquals(t26 - y2k, a.delta)
        assertEquals(listOf(t26, t26 + 5), a.times)
        // a batch wider than the window is clamped per item
        val b = r.shift(listOf(RemintItem(0, 50), RemintItem(200, 50)), 0, 100)
        assertEquals(-100L, b.delta)
        assertEquals(listOf(50L, 100L), b.times)
        // a card created ahead of now bounds the review at now (L = min(created, now))
        val c = r.shift(listOf(RemintItem(500, minOf(400L, 300L)), RemintItem(510, minOf(400L, 300L))), -10_000, 300)
        assertEquals(listOf(300L, 300L), c.times)
        // empty batch
        val e = r.shift(emptyList(), 12345, 99)
        assertEquals(0L, e.delta)
        assertEquals(emptyList(), e.times)
        // clock ahead: negative offset that fits is used as is, spacing kept
        val f = r.shift(listOf(RemintItem(10_000, 0), RemintItem(10_500, 0)), -5_000, 8_000)
        assertEquals(-5_000L, f.delta)
        assertEquals(listOf(5_000L, 5_500L), f.times)
    }
}
