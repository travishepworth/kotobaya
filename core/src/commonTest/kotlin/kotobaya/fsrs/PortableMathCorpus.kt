package kotobaya.fsrs

/**
 * The seeded argument corpus for the `PortableMath` bit-identity tests: ≥ 1 000 000 random
 * arguments (FSRS ranges, wide ranges, raw bit patterns incl. subnormals, |y| > 2^31 with x near 1)
 * plus fdlibm's special cases (±0, ±∞, NaN, ±1, subnormals, the overflow/underflow edges
 * `z ≈ 1024` and `z ≈ −1075` of `e_pow.c`, the thresholds of `e_exp.c`, the `|f| < 2^-20` path of
 * `e_log.c`).
 *
 * Arguments are generated with integer xorshift128 and exact IEEE operations only, so the corpus
 * is the same on every platform. Each function's results are folded into its own [Fnv1a64].
 *
 * To recompute the expected digests (only if the corpus changes), add a temporary jvmTest that
 * prints `PortableMathCorpus.powDigest(StrictMath::pow)` etc. and paste them into
 * [PortableMathTest]; `StrictMath` is specified to return exactly the fdlibm results.
 */
internal object PortableMathCorpus {
    const val RANDOM_POW = 600_000
    const val RANDOM_EXP = 300_000
    const val RANDOM_LN = 300_000

    /** Count of arguments (tuples) each digest covers. */
    val powCount: Int get() = RANDOM_POW + specials.size * specials.size + powEdges().size
    val expCount: Int get() = RANDOM_EXP + specials.size + expEdges().size
    val lnCount: Int get() = RANDOM_LN + specials.size

    fun powDigest(pow: (Double, Double) -> Double): Long {
        val h = Fnv1a64()
        forEachPowArgument { x, y -> h.addDouble(pow(x, y)) }
        return h.value
    }

    fun expDigest(exp: (Double) -> Double): Long {
        val h = Fnv1a64()
        forEachExpArgument { x -> h.addDouble(exp(x)) }
        return h.value
    }

    fun lnDigest(ln: (Double) -> Double): Long {
        val h = Fnv1a64()
        forEachLnArgument { x -> h.addDouble(ln(x)) }
        return h.value
    }

    fun forEachPowArgument(f: (Double, Double) -> Unit) {
        for (x in specials) for (y in specials) f(x, y)
        for ((x, y) in powEdges()) f(x, y)
        val r = XorShift128(0x5EED0001)
        for (i in 0 until RANDOM_POW) {
            when (i % 8) {
                // FSRS ranges: bases in [1e-3, 1e5], exponents in [-10, 10]
                0 -> f(lerp(1e-3, 1e5, r.unit()), lerp(-10.0, 10.0, r.unit()))
                1 -> f(lerp(1e-3, 2.0, r.unit()), lerp(-10.0, 10.0, r.unit()))
                // py-fsrs `math.e ** x`
                2 -> f(E, lerp(-50.0, 50.0, r.unit()))
                // x near 1 (log2 computed through the |x| < sqrt(3/2) / sqrt(3) intervals)
                3 -> f(lerp(0.5, 3.0, r.unit()), lerp(-1000.0, 1000.0, r.unit()))
                // any finite or non-finite x, y with |y| in [2^-60, 2^35)
                4 -> f(r.anyDouble(), r.doubleWithExponent(0x3c3, 0x422))
                // negative x with an integral y (odd and even): sign of result
                5 -> f(-lerp(0.0, 100.0, r.unit()), (r.nextInt() % 400).toDouble())
                // |y| > 2^31 (huge-y branch) with x within 2^-20 of 1, and |y| > 2^64
                6 -> {
                    val x = one + (r.nextInt() shr 11).toDouble() * twoM52
                    val y = r.doubleWithExponent(0x41e, 0x445)
                    f(x, y)
                }
                // subnormal and near-overflow results: 2^y with y in [-1080, 1030], ± tiny x offsets
                else -> {
                    val x = if (r.nextInt() and 1 == 0) two else Double.fromBits(two.toRawBits() + (r.nextInt() shr 20))
                    f(x, lerp(-1080.0, 1030.0, r.unit()))
                }
            }
        }
    }

    fun forEachExpArgument(f: (Double) -> Unit) {
        for (x in specials) f(x)
        for (x in expEdges()) f(x)
        val r = XorShift128(0x5EED0002)
        for (i in 0 until RANDOM_EXP) {
            when (i % 4) {
                0 -> f(lerp(-750.0, 750.0, r.unit()))
                1 -> f(lerp(-1.0, 1.0, r.unit()))
                2 -> f(lerp(-50.0, 50.0, r.unit()))
                else -> f(r.anyDouble())
            }
        }
    }

    fun forEachLnArgument(f: (Double) -> Unit) {
        for (x in specials) f(x)
        val r = XorShift128(0x5EED0003)
        for (i in 0 until RANDOM_LN) {
            when (i % 4) {
                0 -> f(lerp(1e-3, 1e5, r.unit()))
                // every positive bit pattern, subnormals and Inf/NaN included
                1 -> f(Double.fromBits(r.anyDouble().toRawBits() and Long.MAX_VALUE))
                // |f| < 2^-20 path and its boundary, at 1 (k = 0) and at random powers of two (k != 0)
                2 -> {
                    val nearOne = one + lerp(-twoM19, twoM19, r.unit())
                    val scale = Double.fromBits((1L + ((r.nextInt() ushr 1) % 2046)) shl 52)
                    f(if (r.nextInt() and 1 == 0) nearOne else nearOne * scale)
                }
                else -> f(r.anyDouble())
            }
        }
    }

    private val one = Double.fromBits(0x3FF0000000000000L)
    private val two = Double.fromBits(0x4000000000000000L)
    private val twoM52 = Double.fromBits(0x3CB0000000000000L)
    private val twoM19 = Double.fromBits(0x3EC0000000000000L)
    private val E = Double.fromBits(0x4005BF0A8B145769L)

    private fun lerp(a: Double, b: Double, u: Double): Double = a + (b - a) * u

    private fun bits(b: Long): Double = Double.fromBits(b)

    private fun ulps(d: Double, k: Int): Double = Double.fromBits(d.toRawBits() + k)

    /** fdlibm special-case arguments (shared by pow's cross product, exp and ln). */
    val specials: List<Double> = buildList {
        val positives = listOf(
            0.0, Double.MIN_VALUE, bits(0x0000000000000002L), bits(0x0008000000000000L),
            bits(0x000FFFFFFFFFFFFFL), bits(0x0010000000000000L), // largest subnormal, MIN_NORMAL
            1e-300, 2.0e-308, 0.1, 0.25, 0.5, ulps(1.0, -1), 1.0, ulps(1.0, 1),
            1.0 + 1.0 / 1048576.0, 1.0 - 1.0 / 2097152.0, 1.5, 2.0, 3.0, 4.0, E, 10.0, 1023.0, 1024.0,
            1025.0, 1074.0, 1075.0, 1076.0, 1024.5, 1074.5,
            2147483647.0, 2147483648.0, 2147483649.0, 4294967296.0,
            4503599627370497.0, 9007199254740992.0, 9007199254740994.0,
            18446744073709551616.0, ulps(18446744073709551616.0, 1),
            709.782712893384, ulps(709.782712893384, 1), 745.1332191019411, ulps(745.1332191019411, 1),
            bits(0x3FD62E42FEFA39EFL), bits(0x3FF0A2B23F3BAB73L), // 0.5 ln2, 1.5 ln2
            bits(0x3E30000000000000L), bits(0x3E2FFFFFFFFFFFFFL), // 2^-28 and below
            1e300, Double.MAX_VALUE, Double.POSITIVE_INFINITY,
        )
        addAll(positives)
        addAll(positives.map { -it })
        add(Double.NaN)
        add(Double.fromBits(0x7FF0000000000001L)) // signalling NaN pattern
        add(Double.fromBits(-0x0008000000000000L)) // negative NaN 0xFFF8000000000000
    }

    /** Arguments around the overflow (`z ≈ 1024`) and underflow (`z ≈ -1075`) tests of `e_pow.c`. */
    private fun powEdges(): List<Pair<Double, Double>> = buildList {
        val bases = listOf(2.0 to 1024.0, 2.0 to -1075.0, 0.5 to 1075.0, 0.5 to -1024.0,
            -2.0 to 1023.0, -2.0 to -1075.0, 4.0 to 512.0, 4.0 to -537.5, 16.0 to 256.0, 2.0 to -1074.0,
            2.0 to -1022.0, 2.0 to 1023.0, 8.0 to -358.0, 8.0 to 341.5, 0.125 to 358.0)
        for ((x, y) in bases) {
            for (k in -40..40) {
                add(x to ulps(y, k))
                add(ulps(x, k) to y)
            }
        }
    }

    /** Arguments around the reduction and threshold boundaries of `e_exp.c`. */
    private fun expEdges(): List<Double> = buildList {
        val centres = listOf(709.782712893384, -745.1332191019411, -708.3964185322641,
            bits(0x3FD62E42FEFA39EFL), bits(0x3FF0A2B23F3BAB73L), bits(0x3E30000000000000L),
            -bits(0x3FD62E42FEFA39EFL), -bits(0x3FF0A2B23F3BAB73L), -bits(0x3E30000000000000L),
            -707.0, -709.0, -720.0, 0.0)
        for (c in centres) for (k in -40..40) add(ulps(c, k))
    }
}

/** Marsaglia xorshift128 on `Int`s: identical on every platform (no `Long` arithmetic). */
internal class XorShift128(seed: Int) {
    private var a = seed
    private var b = 0x6C078965
    private var c = 0x2545F491
    private var d = 0x1B873593

    init {
        repeat(16) { nextInt() }
    }

    fun nextInt(): Int {
        var t = d
        val s = a
        d = c
        c = b
        b = s
        t = t xor (t shl 11)
        t = t xor (t ushr 8)
        a = t xor s xor (s ushr 19)
        return a
    }

    /** Uniform in `[0, 1)` with 53 random bits (exact arithmetic). */
    fun unit(): Double {
        val hi = (nextInt() ushr 5).toDouble() // 27 bits
        val lo = (nextInt() ushr 6).toDouble() // 26 bits
        return (hi * 67108864.0 + lo) / 9007199254740992.0
    }

    /** Any 64-bit pattern as a double (all exponents, both signs, NaN and Inf included). */
    fun anyDouble(): Double = Double.fromBits((nextInt().toLong() shl 32) or (nextInt().toLong() and 0xFFFFFFFFL))

    /** Random sign and mantissa, biased exponent uniform in `[lo, hi]`. */
    fun doubleWithExponent(lo: Int, hi: Int): Double {
        val e = lo + ((nextInt() ushr 1) % (hi - lo + 1))
        val signAndMantissa = (nextInt().toLong() shl 32) or (nextInt().toLong() and 0xFFFFFFFFL)
        val bits = (signAndMantissa and -0x7FF0000000000001L) or (e.toLong() shl 52)
        return Double.fromBits(bits)
    }
}
