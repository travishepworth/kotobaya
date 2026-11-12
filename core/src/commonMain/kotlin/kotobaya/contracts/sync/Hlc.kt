package kotobaya.contracts.sync

import kotobaya.contracts.HlcString
import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid

/**
 * The three parts of an HLC (SYNC.md 3.1).
 *
 * Example: `0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1` is
 * `HlcParts(ms = 0x0193a1b2c3d4, counter = 1, deviceId = "01931f3e-0000-7000-8000-0000000000a1")`.
 */
public data class HlcParts(
    /** Wall-clock part, ms since the epoch; `0 ..= 0xffffffffffff` (12 hex digits). */
    public val ms: Long,
    /** Counter, `0 ..= 0xffff` (4 hex digits). */
    public val counter: Int,
    /** Canonical lowercase UUID of the replica that minted the value. */
    public val deviceId: Uuid,
)

/**
 * A replica's clock state `last = (ms, ctr)` of SYNC.md 3.2. The device part is not stored here:
 * it is always the replica's own id. Persisted by the caller (device: `sync_state.hlc_last` holds
 * the full HLC string of the last minted or observed value) **in the same transaction** as the op
 * that was minted or applied.
 */
public data class HlcState(
    /** `last.ms`. */
    public val ms: Long,
    /** `last.ctr`. */
    public val counter: Int,
) {
    /** Constants. */
    public companion object {
        /** The state of a replica that has never minted or observed anything: `(0, 0)` (SYNC.md 3.2). */
        public val INITIAL: HlcState = HlcState(0L, 0)
    }
}

/** Result of [HlcRules.mint] / [HlcRules.mintAt]: the new value and the state to persist. */
public data class Minted(
    /** The minted HLC, formatted with the replica's own device id. */
    public val hlc: HlcString,
    /** The new `last`; the caller persists it with the op. */
    public val state: HlcState,
)

/** Outcome of the device-local clock guard of SYNC.md 3.4. */
public enum class ClockGuardResult {
    /** Neither guard holds: minting is allowed (as far as the clock is concerned). */
    OK,

    /** G1: `pt < FLOOR` — the clock was reset to 1970/2000. Minting is refused. */
    G1_BEFORE_FLOOR,

    /** G2: `pt < wall(hlc_last) − 24 h` — the clock is far behind a time already minted or observed. Minting is refused. */
    G2_BEHIND_LAST,
}

/**
 * Pure HLC functions (SYNC.md 3). Stateless: the caller holds and persists [HlcState].
 *
 * Invariants every implementation guarantees:
 * - [format] and [parse] are inverse on every valid value; [parse] accepts only the canonical
 *   lowercase 54-character form (`common.schema.json#/$defs/hlc`).
 * - [compare] is byte-wise string comparison, i.e. `a.compareTo(b)`. It orders by ms, then counter,
 *   then device id. Never compare HLCs numerically by parts in other code: use strings.
 * - [mint] and [mintAt] always return a value strictly greater (in `(ms, ctr)`) than the input state,
 *   so a replica's HLCs are strictly increasing in mint order and never repeat.
 */
public interface HlcRules {
    /**
     * Formats parts as the 54-character HLC: 12 lowercase hex digits of `ms` (zero-padded), `-`,
     * 4 lowercase hex digits of `counter`, `-`, the device id.
     *
     * @throws IllegalArgumentException if `ms` is outside `0 ..= 0xffffffffffff`, `counter` outside
     *   `0 ..= 0xffff`, or `deviceId` is not a canonical lowercase UUID.
     */
    public fun format(parts: HlcParts): HlcString

    /**
     * Parses an HLC. Returns null (never throws) for anything that is not exactly the canonical
     * form: wrong length, uppercase hex, missing dashes, a non-canonical device UUID.
     *
     * Example: `parse("0193a1b2c3d4-0001-01931f3e-0000-7000-8000-0000000000a1")` →
     * `HlcParts(1733584667604, 1, "01931f3e-0000-7000-8000-0000000000a1")`.
     */
    public fun parse(hlc: String): HlcParts?

    /**
     * `wall(h)` of SYNC.md 1: the ms part, `parseInt(h[0..12], 16)`.
     *
     * @throws IllegalArgumentException if [hlc] does not [parse].
     */
    public fun wall(hlc: HlcString): InstantMs

    /** Byte-wise comparison: negative if `a < b`, 0 if equal, positive if `a > b`. Equal means "the same op" (SYNC.md 3.3). */
    public fun compare(a: HlcString, b: HlcString): Int

    /**
     * The state that a stored full HLC string represents: `(ms, counter)` of [hlc], device part
     * dropped. Used to load `sync_state.hlc_last`; `null` input (key absent) gives [HlcState.INITIAL].
     *
     * @throws IllegalArgumentException if [hlc] is non-null and does not [parse].
     */
    public fun stateOf(hlc: HlcString?): HlcState

    /**
     * `mint()` of SYNC.md 3.2 with physical time [physicalMs] (`pt`):
     * ```
     * if pt > last.ms:            last = (pt, 0)
     * else if last.ctr < 0xffff:  last = (last.ms, last.ctr + 1)
     * else:                       last = (last.ms + 1, 0)
     * ```
     * Returns `format(last, deviceId)` and the new `last`.
     *
     * Examples (device `D`): state `(1000, 0)`, pt `2000` → `(2000, 0)`; state `(1000, 5)`, pt `900`
     * (clock behind) → `(1000, 6)`; state `(1000, 0xffff)`, pt `1000` → `(1001, 0)`.
     *
     * The caller MUST have checked [guard] first on a device (SYNC.md 4); this function does not.
     *
     * @throws IllegalStateException if the result's ms would exceed `0xffffffffffff`.
     */
    public fun mint(state: HlcState, physicalMs: InstantMs, deviceId: Uuid): Minted

    /**
     * `mint_at(v)` of SYNC.md 3.2: [mint] with [v] in place of `pt`. Used **only** by Re-mint
     * (SYNC.md 10.4 step 6a). Still strictly above [state]; when `v <= state.ms` the value lands just
     * above `last` exactly as [mint] would with a physical clock that is behind.
     */
    public fun mintAt(state: HlcState, v: InstantMs, deviceId: Uuid): Minted

    /**
     * `observe(h)` of SYNC.md 3.2: returns `(h.ms, h.ctr)` if it is greater than `(last.ms, last.ctr)`,
     * otherwise [state] unchanged. The device part of [hlc] is ignored. Called for the envelope
     * `hlc` of every applied op the replica did not mint (device: every pulled op; server: every
     * accepted pushed op), before minting again. Never called for `review.logged.replay_hlc`.
     *
     * @throws IllegalArgumentException if [hlc] does not [parse].
     */
    public fun observe(state: HlcState, hlc: HlcString): HlcState

    /**
     * Device guard of SYNC.md 3.4, checked before every mint, on app start and before a review
     * session. G1 is checked first: `pt < 1577836800000` → [ClockGuardResult.G1_BEFORE_FLOOR];
     * else `hlcLast != null && pt < wall(hlcLast) − 86400000` → [ClockGuardResult.G2_BEHIND_LAST];
     * else [ClockGuardResult.OK]. The boundaries themselves (`pt == FLOOR`,
     * `pt == wall − 24 h`) are OK.
     */
    public fun guard(physicalMs: InstantMs, hlcLast: HlcString?): ClockGuardResult
}

/** One item of a re-mint batch (SYNC.md 10.4 step 4): its time `t` and lower bound `L`. */
public data class RemintItem(
    /** `t`: `wall(e.hlc)` for an op of `E`; `reviewed_at` for a review. */
    public val t: InstantMs,
    /** `L`: `FLOOR`, or `min(card.created_at, now)` for a review of a card not created in the window. */
    public val lowerBound: InstantMs,
)

/** Result of [RemintRules.shift]. */
public data class RemintShift(
    /** `delta` of SYNC.md 10.4 step 5 (signed ms). */
    public val delta: Long,
    /** `t'` per input item, same order and length as the input: `clamp(t + delta, L, now)`. */
    public val times: List<InstantMs>,
)

/** The pure arithmetic of Re-mint (SYNC.md 10.4 step 5). The surrounding procedure is T202's. */
public interface RemintRules {
    /**
     * ```
     * d_hi  = now − max(t)
     * d_lo  = max(L − t)
     * delta = min(max(offset, d_lo), d_hi)
     * t'    = clamp(t + delta, L, now)      // per item; clamp(x, lo, hi) = max(lo, min(x, hi))
     * ```
     * [offset] is `sync_state.remint_offset` (0 when absent). An empty [items] list returns
     * `RemintShift(0, [])`. Must reproduce the `remint` block of scenario
     * `contracts/sync/examples/scenarios/clock-behind-remint.json`.
     */
    public fun shift(items: List<RemintItem>, offset: Long, now: InstantMs): RemintShift
}
