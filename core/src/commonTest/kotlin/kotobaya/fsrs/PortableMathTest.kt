package kotobaya.fsrs

import kotobaya.contracts.fsrs.PortableMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on jvm, js (node) and android unit tests. The expected digests were computed once on the
 * JVM with `java.lang.StrictMath` (= fdlibm 5.3) over [PortableMathCorpus]; matching them proves
 * `FdlibmMath` is bit-identical to `StrictMath` on every platform this test runs on.
 */
class PortableMathTest {
    private val m: PortableMath = FdlibmMath

    private fun hex(v: Long): String = "0x" + v.toULong().toString(16)

    @Test
    fun corpusHasAtLeastOneMillionArgumentsPlusSpecials() {
        val total = PortableMathCorpus.powCount + PortableMathCorpus.expCount + PortableMathCorpus.lnCount
        assertTrue(total >= 1_000_000, "corpus size $total")
        assertTrue(PortableMathCorpus.specials.size >= 100)
    }

    @Test
    fun powIsBitEqualToStrictMathOnCorpus() {
        assertEquals(hex(STRICT_POW_DIGEST), hex(PortableMathCorpus.powDigest(m::pow)))
    }

    @Test
    fun expIsBitEqualToStrictMathOnCorpus() {
        assertEquals(hex(STRICT_EXP_DIGEST), hex(PortableMathCorpus.expDigest(m::exp)))
    }

    @Test
    fun lnIsBitEqualToStrictMathOnCorpus() {
        assertEquals(hex(STRICT_LN_DIGEST), hex(PortableMathCorpus.lnDigest(m::ln)))
    }

    @Test
    fun powSpecialCasesFromFdlibmHeader() {
        val nan = Double.NaN
        val inf = Double.POSITIVE_INFINITY
        assertBits(1.0, m.pow(nan, 0.0)) // (anything) ** 0 is 1
        assertBits(1.0, m.pow(nan, -0.0))
        assertBits(3.0, m.pow(3.0, 1.0)) // (anything) ** 1 is itself
        assertTrue(m.pow(1.0, nan).isNaN()) // fdlibm 5.3: (anything) ** NaN is NaN
        assertTrue(m.pow(nan, 1.5).isNaN())
        assertBits(inf, m.pow(1.5, inf)) // |x| > 1 ** +inf
        assertBits(0.0, m.pow(1.5, -inf))
        assertBits(0.0, m.pow(0.5, inf)) // |x| < 1 ** +inf
        assertBits(inf, m.pow(0.5, -inf))
        assertTrue(m.pow(1.0, inf).isNaN()) // +-1 ** +-inf is NaN
        assertTrue(m.pow(-1.0, -inf).isNaN())
        assertBits(0.0, m.pow(0.0, 2.5)) // +0 ** (+anything except 0, NaN) is +0
        assertBits(-0.0, m.pow(-0.0, 3.0)) // -0 ** (odd integer) is -0
        assertBits(0.0, m.pow(-0.0, 2.5))
        assertBits(inf, m.pow(0.0, -2.5)) // +-0 ** (-anything) is +inf, -0 ** (-odd) is -inf
        assertBits(-inf, m.pow(-0.0, -3.0))
        assertBits(inf, m.pow(inf, 0.5))
        assertBits(0.0, m.pow(inf, -0.5))
        assertBits(-inf, m.pow(-inf, 3.0)) // -inf ** odd
        assertBits(-0.0, m.pow(-inf, -3.0))
        assertBits(-8.0, m.pow(-2.0, 3.0)) // (-anything) ** (integer) is (-1)**(integer)*(+anything**integer)
        assertTrue(m.pow(-2.0, 0.5).isNaN()) // (-anything except 0 and inf) ** (non-integer) is NaN
        assertBits(Double.MIN_VALUE, m.pow(2.0, -1074.0)) // subnormal output via scalbn
        assertBits(inf, m.pow(2.0, 1024.0))
    }

    @Test
    fun expAndLnSpecialCases() {
        val inf = Double.POSITIVE_INFINITY
        assertBits(1.0, m.exp(0.0))
        assertBits(inf, m.exp(inf))
        assertBits(0.0, m.exp(-inf))
        assertTrue(m.exp(Double.NaN).isNaN())
        assertBits(inf, m.exp(710.0))
        assertBits(0.0, m.exp(-746.0))
        assertBits(-inf, m.ln(0.0))
        assertBits(-inf, m.ln(-0.0))
        assertTrue(m.ln(-1.0).isNaN())
        assertBits(0.0, m.ln(1.0))
        assertBits(inf, m.ln(inf))
        assertBits(-744.4400719213812, m.ln(Double.MIN_VALUE))
    }

    @Test
    fun eConstantHasPythonMathEBits() {
        assertEquals(0x4005BF0A8B145769L, PortableMath.E.toRawBits())
    }

    @Test
    fun roundHalfEvenTiesGoToEven() {
        assertEquals(2.0, roundHalfEven(2.5))
        assertEquals(4.0, roundHalfEven(3.5))
        assertEquals(0.0, roundHalfEven(0.5))
        assertEquals(2.0, roundHalfEven(2.4999999999999996))
        assertEquals(3.0, roundHalfEven(2.5000000000000004))
        assertEquals(-2.0, roundHalfEven(-2.5))
        assertEquals(-4.0, roundHalfEven(-3.5))
        assertEquals(7.0, roundHalfEven(7.0))
    }

    @Test
    fun fnv1a64MatchesPublishedVectors() {
        assertEquals("0xcbf29ce484222325", hex(Fnv1a64.ofBytes(ByteArray(0))))
        assertEquals("0xaf63dc4c8601ec8c", hex(Fnv1a64.ofBytes(byteArrayOf('a'.code.toByte()))))
        assertEquals("0x85944171f73967e8", hex(Fnv1a64.ofBytes("foobar".encodeToByteArray())))
        // addLong folds the word's bytes least significant first
        assertEquals(
            Fnv1a64.ofBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
            Fnv1a64().addLong(0x0807060504030201L).value,
        )
        // every NaN folds the same
        assertEquals(Fnv1a64().addDouble(Double.NaN).value, Fnv1a64().addDouble(Double.fromBits(-1L)).value)
    }

    private fun assertBits(expected: Double, actual: Double) {
        assertEquals(hex(expected.toRawBits()), hex(actual.toRawBits()), "expected $expected, got $actual")
    }

    internal companion object {
        // Computed once on the JVM: PortableMathCorpus.powDigest(StrictMath::pow) etc.
        const val STRICT_POW_DIGEST: Long = -0x0a4a24d41e0c9c56L // 0xf5b5db2be1f363aa
        const val STRICT_EXP_DIGEST: Long = -0x31cd37f0587f1faeL // 0xce32c80fa780e052
        const val STRICT_LN_DIGEST: Long = 0x3bdace39bbb903ceL
    }
}
