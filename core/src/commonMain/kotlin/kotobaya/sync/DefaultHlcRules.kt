package kotobaya.sync

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Reserved
import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.ClockGuardResult
import kotobaya.contracts.sync.HlcParts
import kotobaya.contracts.sync.HlcRules
import kotobaya.contracts.sync.HlcState
import kotobaya.contracts.sync.Minted
import kotobaya.contracts.sync.RemintItem
import kotobaya.contracts.sync.RemintRules
import kotobaya.contracts.sync.RemintShift

/** [HlcRules] exactly as SYNC.md 3.1–3.4. Pure: no clock, no I/O; the caller passes `pt` and persists the state. */
public object DefaultHlcRules : HlcRules {
    /** Largest `ms` part: 12 hex digits. */
    public const val MAX_MS: Long = 0xffffffffffffL

    /** Largest counter: 4 hex digits. */
    public const val MAX_COUNTER: Int = 0xffff

    private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val HLC = Regex("^[0-9a-f]{12}-[0-9a-f]{4}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** True for a canonical lowercase UUID (`common.schema.json#/$defs/uuid`). */
    public fun isUuid(s: String): Boolean = s.length == 36 && UUID.matches(s)

    override fun format(parts: HlcParts): HlcString {
        require(parts.ms in 0..MAX_MS) { "HLC ms out of range: ${parts.ms}" }
        require(parts.counter in 0..MAX_COUNTER) { "HLC counter out of range: ${parts.counter}" }
        require(isUuid(parts.deviceId)) { "not a canonical lowercase UUID: ${parts.deviceId}" }
        return parts.ms.toString(16).padStart(12, '0') + "-" +
            parts.counter.toString(16).padStart(4, '0') + "-" + parts.deviceId
    }

    override fun parse(hlc: String): HlcParts? {
        if (hlc.length != 54 || !HLC.matches(hlc)) return null
        return HlcParts(hlc.substring(0, 12).toLong(16), hlc.substring(13, 17).toInt(16), hlc.substring(18))
    }

    private fun parsed(hlc: String): HlcParts = requireNotNull(parse(hlc)) { "not a canonical HLC: $hlc" }

    override fun wall(hlc: HlcString): InstantMs = parsed(hlc).ms

    override fun compare(a: HlcString, b: HlcString): Int = a.compareTo(b)

    override fun stateOf(hlc: HlcString?): HlcState {
        if (hlc == null) return HlcState.INITIAL
        val p = parsed(hlc)
        return HlcState(p.ms, p.counter)
    }

    override fun mint(state: HlcState, physicalMs: InstantMs, deviceId: Uuid): Minted {
        require(isUuid(deviceId)) { "not a canonical lowercase UUID: $deviceId" }
        require(state.ms in 0..MAX_MS && state.counter in 0..MAX_COUNTER) { "invalid HLC state $state" }
        val next = when {
            physicalMs > state.ms -> HlcState(physicalMs, 0)
            state.counter < MAX_COUNTER -> HlcState(state.ms, state.counter + 1)
            else -> HlcState(state.ms + 1, 0)
        }
        check(next.ms <= MAX_MS) { "HLC ms would exceed 0xffffffffffff" }
        return Minted(format(HlcParts(next.ms, next.counter, deviceId)), next)
    }

    override fun mintAt(state: HlcState, v: InstantMs, deviceId: Uuid): Minted = mint(state, v, deviceId)

    override fun observe(state: HlcState, hlc: HlcString): HlcState {
        val p = parsed(hlc)
        val greater = p.ms > state.ms || (p.ms == state.ms && p.counter > state.counter)
        return if (greater) HlcState(p.ms, p.counter) else state
    }

    override fun guard(physicalMs: InstantMs, hlcLast: HlcString?): ClockGuardResult = when {
        physicalMs < Reserved.CLOCK_FLOOR_MS -> ClockGuardResult.G1_BEFORE_FLOOR
        hlcLast != null && physicalMs < wall(hlcLast) - Reserved.CLOCK_TOLERANCE_MS -> ClockGuardResult.G2_BEHIND_LAST
        else -> ClockGuardResult.OK
    }
}

/** [RemintRules]: the shift arithmetic of SYNC.md 10.4 step 5. */
public object DefaultRemintRules : RemintRules {
    override fun shift(items: List<RemintItem>, offset: Long, now: InstantMs): RemintShift {
        if (items.isEmpty()) return RemintShift(0, emptyList())
        val dHi = now - items.maxOf { it.t }
        val dLo = items.maxOf { it.lowerBound - it.t }
        val delta = minOf(maxOf(offset, dLo), dHi)
        return RemintShift(delta, items.map { maxOf(it.lowerBound, minOf(it.t + delta, now)) })
    }
}
