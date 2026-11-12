package kotobaya.contracts.fsrs

/**
 * Transcendental functions that give **bit-identical** results on every platform the core runs on
 * (JVM, Android/ART, JS/V8). Required by [Scheduler] and [kotobaya.contracts.replay.ReplayEngine].
 *
 * **Why.** `kotlin.math.pow/exp/ln` map to each platform's libm (HotSpot intrinsics, bionic, V8's
 * ieee754 port), which may differ by one ulp. A one-ulp difference in a stored `stability` makes
 * the caches of two replicas differ bitwise, and an interval that lands near `k + 0.5` before
 * rounding is then a whole day apart between phone and server, permanently (SYNC.md 11.3 requires
 * caches to converge). Only IEEE 754 basic operations (`+ − × ÷`, `sqrt`, comparisons) are
 * correctly rounded, and therefore identical, everywhere.
 *
 * **Implementation (normative).** One pure-Kotlin implementation in `commonMain` (no `expect`/
 * `actual`), a line-by-line port of **fdlibm 5.3** — `e_pow.c` (`__ieee754_pow`), `e_exp.c`
 * (`__ieee754_exp`), `e_log.c` (`__ieee754_log`) — using only IEEE basic operations, `Double`
 * comparisons, `Double.toRawBits()` / `Double.fromBits()` and `Int`/`Long` bit operations. No
 * `kotlin.math` transcendental, no `Math.*`, no fused multiply-add. Because `java.lang.StrictMath`
 * is specified to return exactly the fdlibm results, the port is checked bit-for-bit against
 * `StrictMath` on the JVM.
 *
 * **Banned** in the implementation of [Scheduler], [kotobaya.contracts.replay.ReplayEngine] and
 * anything they call: `kotlin.math.pow`, `Double.pow`, `exp`, `expm1`, `ln`, `ln1p`, `log*`,
 * `sin`/`cos`/`tan` and their inverses, `hypot`, `cbrt`, `sinh`/`cosh`/`tanh`, and any `Math.*` or
 * `js("Math…")` call; also `roundToInt`/`roundToLong` (half-up, not what py-fsrs does). Allowed:
 * the basic operations, `kotlin.math.sqrt`, `floor`, `ceil`, `abs`, `min`, `max`, `sign`, `withSign`.
 *
 * Required tests (T107): on JVM, `pow`, `exp`, `ln` equal `StrictMath.pow/exp/log` bit-for-bit for
 * ≥ 1 000 000 seeded random arguments over the ranges FSRS uses (bases in `[1e-3, 1e5]`, exponents
 * in `[-10, 10]`) plus the special cases of fdlibm's header comment (±0, ±∞, NaN, 1, −1, subnormals);
 * the same seeded arguments hashed (FNV-1a 64 over `toRawBits()`) give the same digest on jvm and js.
 */
public interface PortableMath {
    /**
     * `x` raised to `y`, exactly as fdlibm 5.3 `__ieee754_pow` (= `StrictMath.pow`), including its
     * special cases (`pow(x, 0) = 1` for every x incl. NaN; `pow(1, y) = 1`; negative x with
     * non-integral y → NaN).
     */
    public fun pow(x: Double, y: Double): Double

    /** `e^x`, exactly as fdlibm 5.3 `__ieee754_exp` (= `StrictMath.exp`). */
    public fun exp(x: Double): Double

    /** Natural logarithm, exactly as fdlibm 5.3 `__ieee754_log` (= `StrictMath.log`); `ln(0) = −∞`, negative → NaN. */
    public fun ln(x: Double): Double

    /** Constants. */
    public companion object {
        /**
         * Euler's number as the double `2.718281828459045` (bits `0x4005BF0A8B145769`, equal to
         * Python's `math.e`). py-fsrs writes `math.e ** x`; the scheduler evaluates that as
         * `pow(E, x)`, not `exp(x)` (the two can differ by an ulp).
         */
        public const val E: Double = 2.718281828459045
    }
}
