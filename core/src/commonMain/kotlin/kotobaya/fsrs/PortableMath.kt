package kotobaya.fsrs

import kotobaya.contracts.fsrs.PortableMath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Pure-Kotlin port of fdlibm 5.3 `__ieee754_pow` (`e_pow.c`), `__ieee754_exp` (`e_exp.c`),
 * `__ieee754_log` (`e_log.c`) and `scalbn` (`s_scalbn.c`); bit-identical to `java.lang.StrictMath`
 * on every platform (see [PortableMath]). Only IEEE basic operations, `sqrt`, comparisons and
 * word access through `toRawBits`/`fromBits` are used. Every constant is built from fdlibm's hex
 * words (the decimal value from fdlibm's source is kept as a comment).
 *
 * The code follows the C line by line; variable names are fdlibm's. C `unsigned` words are Kotlin
 * `Int`s: they are only ever compared for equality, OR-ed, or shifted with `ushr`, so the
 * signedness does not change any result.
 */
internal object FdlibmMath : PortableMath {

    // ---- shared constants -------------------------------------------------------------------
    private val zero = word(0x00000000, 0x00000000) // 0.0
    private val half = word(0x3FE00000, 0x00000000) // 0.5
    private val one = word(0x3FF00000, 0x00000000) // 1.0
    private val two = word(0x40000000, 0x00000000) // 2.0
    private val three = word(0x40080000, 0x00000000) // 3.0
    private val quarter = word(0x3FD00000, 0x00000000) // 0.25
    private val third = word(0x3FD55555, 0x55555555) // 0.3333333333333333333333
    private val huge = word(0x7E37E43C, 0x8800759C) // 1.0e300
    private val tiny = word(0x01A56E1F, 0xC2F8F359) // 1.0e-300
    private val two53 = word(0x43400000, 0x00000000) // 9007199254740992.0
    private val two54 = word(0x43500000, 0x00000000) // 1.80143985094819840000e+16
    private val twom54 = word(0x3C900000, 0x00000000) // 5.55111512312578270212e-17

    // ---- e_pow.c ----------------------------------------------------------------------------
    private val bp0 = one // 1.0
    private val bp1 = word(0x3FF80000, 0x00000000) // 1.5
    private val dpH0 = zero
    private val dpH1 = word(0x3FE2B803, 0x40000000) // 5.84962487220764160156e-01
    private val dpL0 = zero
    private val dpL1 = word(0x3E4CFDEB, 0x43CFD006) // 1.35003920212974897128e-08

    // poly coefs for (3/2)*(log(x)-2s-2/3*s**3)
    private val L1 = word(0x3FE33333, 0x33333303) // 5.99999999999994648725e-01
    private val L2 = word(0x3FDB6DB6, 0xDB6FABFF) // 4.28571428578550184252e-01
    private val L3 = word(0x3FD55555, 0x518F264D) // 3.33333329818377432918e-01
    private val L4 = word(0x3FD17460, 0xA91D4101) // 2.72728123808534006489e-01
    private val L5 = word(0x3FCD864A, 0x93C9DB65) // 2.30660745775561754067e-01
    private val L6 = word(0x3FCA7E28, 0x4A454EEF) // 2.06975017800338417784e-01
    private val P1 = word(0x3FC55555, 0x5555553E) // 1.66666666666666019037e-01
    private val P2 = word(0xBF66C16C, 0x16BEBD93) // -2.77777777770155933842e-03
    private val P3 = word(0x3F11566A, 0xAF25DE2C) // 6.61375632143793436117e-05
    private val P4 = word(0xBEBBBD41, 0xC5D26BF1) // -1.65339022054652515390e-06
    private val P5 = word(0x3E663769, 0x72BEA4D0) // 4.13813679705723846039e-08
    private val lg2 = word(0x3FE62E42, 0xFEFA39EF) // 6.93147180559945286227e-01
    private val lg2H = word(0x3FE62E43, 0x00000000) // 6.93147182464599609375e-01
    private val lg2L = word(0xBE205C61, 0x0CA86C39) // -1.90465429995776804525e-09
    private val ovt = word(0x3C971547, 0x652B82FE) // 8.0085662595372944372e-17
    private val cp = word(0x3FEEC709, 0xDC3A03FD) // 9.61796693925975554329e-01
    private val cpH = word(0x3FEEC709, 0xE0000000) // 9.61796700954437255859e-01
    private val cpL = word(0xBE3E2FE0, 0x145B01F5) // -7.02846165095275826516e-09
    private val ivln2 = word(0x3FF71547, 0x652B82FE) // 1.44269504088896338700e+00
    private val ivln2H = word(0x3FF71547, 0x60000000) // 1.44269502162933349609e+00
    private val ivln2L = word(0x3E54AE0B, 0xF85DDF44) // 1.92596299112661746887e-08

    // ---- e_exp.c ----------------------------------------------------------------------------
    private val twom1000 = word(0x01700000, 0x00000000) // 9.33263618503218878990e-302
    private val oThreshold = word(0x40862E42, 0xFEFA39EF) // 7.09782712893383973096e+02
    private val uThreshold = word(0xC0874910, 0xD52D3051) // -7.45133219101941108420e+02
    private val ln2HI = word(0x3FE62E42, 0xFEE00000) // 6.93147180369123816490e-01
    private val ln2LO = word(0x3DEA39EF, 0x35793C76) // 1.90821492927058770002e-10

    // ---- e_log.c ----------------------------------------------------------------------------
    private val Lg1 = word(0x3FE55555, 0x55555593) // 6.666666666666735130e-01
    private val Lg2 = word(0x3FD99999, 0x9997FA04) // 3.999999999940941908e-01
    private val Lg3 = word(0x3FD24924, 0x94229359) // 2.857142874366239149e-01
    private val Lg4 = word(0x3FCC71C5, 0x1D8E78AF) // 2.222219843214978396e-01
    private val Lg5 = word(0x3FC74664, 0x96CB03DE) // 1.818357216161805012e-01
    private val Lg6 = word(0x3FC39A09, 0xD078C69F) // 1.531383769920937332e-01
    private val Lg7 = word(0x3FC2F112, 0xDF3E5244) // 1.479819860511658591e-01

    override fun pow(x: Double, y: Double): Double {
        var z: Double
        var ax: Double
        val zH: Double
        val zL: Double
        var pH: Double
        var pL: Double
        var t1: Double
        val t2: Double
        var r: Double
        var t: Double
        var u: Double
        var v: Double
        val w: Double
        var i: Int
        var j: Int
        var k: Int
        var n: Int

        val hx = hi(x)
        val lx = lo(x)
        val hy = hi(y)
        val ly = lo(y)
        var ix = hx and 0x7fffffff
        val iy = hy and 0x7fffffff

        // y==zero: x**0 = 1
        if ((iy or ly) == 0) return one

        // +-NaN return x+y
        if (ix > 0x7ff00000 || ((ix == 0x7ff00000) && (lx != 0)) ||
            iy > 0x7ff00000 || ((iy == 0x7ff00000) && (ly != 0))
        ) {
            return x + y
        }

        // determine if y is an odd int when x < 0
        // yisint = 0 ... y is not an integer; 1 ... y is an odd int; 2 ... y is an even int
        var yisint = 0
        if (hx < 0) {
            if (iy >= 0x43400000) {
                yisint = 2 // even integer y
            } else if (iy >= 0x3ff00000) {
                k = (iy shr 20) - 0x3ff // exponent
                if (k > 20) {
                    j = ly ushr (52 - k) // C: unsigned ly >> (52-k)
                    if ((j shl (52 - k)) == ly) yisint = 2 - (j and 1)
                } else if (ly == 0) {
                    j = iy shr (20 - k)
                    if ((j shl (20 - k)) == iy) yisint = 2 - (j and 1)
                }
            }
        }

        // special value of y
        if (ly == 0) {
            if (iy == 0x7ff00000) { // y is +-inf
                return if (((ix - 0x3ff00000) or lx) == 0) {
                    y - y // inf**+-1 is NaN
                } else if (ix >= 0x3ff00000) { // (|x|>1)**+-inf = inf,0
                    if (hy >= 0) y else zero
                } else { // (|x|<1)**-,+inf = inf,0
                    if (hy < 0) -y else zero
                }
            }
            if (iy == 0x3ff00000) { // y is +-1
                return if (hy < 0) one / x else x
            }
            if (hy == 0x40000000) return x * x // y is 2
            if (hy == 0x3fe00000) { // y is 0.5
                if (hx >= 0) return sqrt(x) // x >= +0
            }
        }

        ax = abs(x)
        // special value of x
        if (lx == 0) {
            if (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) {
                z = ax // x is +-0,+-inf,+-1
                if (hy < 0) z = one / z // z = (1/|x|)
                if (hx < 0) {
                    if (((ix - 0x3ff00000) or yisint) == 0) {
                        z = (z - z) / (z - z) // (-1)**non-int is NaN
                    } else if (yisint == 1) {
                        z = -z // (x<0)**odd = -(|x|**odd)
                    }
                }
                return z
            }
        }

        n = (hx shr 31) + 1

        // (x<0)**(non-int) is NaN
        if ((n or yisint) == 0) return (x - x) / (x - x)

        var s = one // s (sign of result -ve**odd) = -1 else = 1
        if ((n or (yisint - 1)) == 0) s = -one // (-ve)**(odd int)

        // |y| is huge
        if (iy > 0x41e00000) { // if |y| > 2**31
            if (iy > 0x43f00000) { // if |y| > 2**64, must o/uflow
                if (ix <= 0x3fefffff) return if (hy < 0) huge * huge else tiny * tiny
                if (ix >= 0x3ff00000) return if (hy > 0) huge * huge else tiny * tiny
            }
            // over/underflow if x is not close to one
            if (ix < 0x3fefffff) return if (hy < 0) s * huge * huge else s * tiny * tiny
            if (ix > 0x3ff00000) return if (hy > 0) s * huge * huge else s * tiny * tiny
            // now |1-x| is tiny <= 2**-20, suffice to compute log(x) by x-x^2/2+x^3/3-x^4/4
            t = ax - one // t has 20 trailing zeros
            val ww = (t * t) * (half - t * (third - t * quarter))
            u = ivln2H * t // ivln2_h has 21 sig. bits
            v = t * ivln2L - ww * ivln2
            t1 = withLo(u + v, 0)
            t2 = v - (t1 - u)
        } else {
            n = 0
            // take care subnormal number
            if (ix < 0x00100000) {
                ax *= two53
                n -= 53
                ix = hi(ax)
            }
            n += (ix shr 20) - 0x3ff
            j = ix and 0x000fffff
            // determine interval
            ix = j or 0x3ff00000 // normalize ix
            if (j <= 0x3988E) {
                k = 0 // |x|<sqrt(3/2)
            } else if (j < 0xBB67A) {
                k = 1 // |x|<sqrt(3)
            } else {
                k = 0
                n += 1
                ix -= 0x00100000
            }
            ax = withHi(ax, ix)
            val bpk = if (k == 0) bp0 else bp1

            // compute ss = s_h+s_l = (x-1)/(x+1) or (x-1.5)/(x+1.5)
            u = ax - bpk // bp[0]=1.0, bp[1]=1.5
            v = one / (ax + bpk)
            val ss = u * v
            val sH = withLo(ss, 0)
            // t_h=ax+bp[k] High
            var tH = fromWords(((ix shr 1) or 0x20000000) + 0x00080000 + (k shl 18), 0)
            var tL = ax - (tH - bpk)
            val sL = v * ((u - sH * tH) - sH * tL)
            // compute log(ax)
            var s2 = ss * ss
            r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))))
            r += sL * (sH + ss)
            s2 = sH * sH
            tH = withLo(three + s2 + r, 0)
            tL = r - ((tH - three) - s2)
            // u+v = ss*(1+...)
            u = sH * tH
            v = sL * tH + tL * ss
            // 2/(3log2)*(ss+...)
            pH = withLo(u + v, 0)
            pL = v - (pH - u)
            zH = cpH * pH // cp_h+cp_l = 2/(3*log2)
            zL = cpL * pH + pL * cp + (if (k == 0) dpL0 else dpL1)
            // log2(ax) = (ss+..)*2/(3*log2) = n + dp_h + z_h + z_l
            val dpHk = if (k == 0) dpH0 else dpH1
            t = n.toDouble()
            t1 = withLo(((zH + zL) + dpHk) + t, 0)
            t2 = zL - (((t1 - t) - dpHk) - zH)
        }

        // split up y into y1+y2 and compute (y1+y2)*(t1+t2)
        val y1 = withLo(y, 0)
        pL = (y - y1) * t1 + y * t2
        pH = y1 * t1
        z = pL + pH
        j = hi(z)
        i = lo(z)
        if (j >= 0x40900000) { // z >= 1024
            if (((j - 0x40900000) or i) != 0) { // if z > 1024
                return s * huge * huge // overflow
            } else {
                if (pL + ovt > z - pH) return s * huge * huge // overflow
            }
        } else if ((j and 0x7fffffff) >= 0x4090cc00) { // z <= -1075
            if (((j - 0xc090cc00.toInt()) or i) != 0) { // z < -1075 (C: j-0xc090cc00, unsigned)
                return s * tiny * tiny // underflow
            } else {
                if (pL <= z - pH) return s * tiny * tiny // underflow
            }
        }

        // compute 2**(p_h+p_l)
        i = j and 0x7fffffff
        k = (i shr 20) - 0x3ff
        n = 0
        if (i > 0x3fe00000) { // if |z| > 0.5, set n = [z+0.5]
            n = j + (0x00100000 shr (k + 1))
            k = ((n and 0x7fffffff) shr 20) - 0x3ff // new k for n
            t = fromWords(n and (0x000fffff shr k).inv(), 0)
            n = ((n and 0x000fffff) or 0x00100000) shr (20 - k)
            if (j < 0) n = -n
            pH -= t
        }
        t = withLo(pL + pH, 0)
        u = t * lg2H
        v = (pL - (t - pH)) * lg2 + t * lg2L
        z = u + v
        w = v - (z - u)
        t = z * z
        t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))
        r = (z * t1) / (t1 - two) - (w + z * w)
        z = one - (r - z)
        j = hi(z)
        j += (n shl 20)
        z = if ((j shr 20) <= 0) {
            scalbn(z, n) // subnormal output
        } else {
            withHi(z, hi(z) + (n shl 20))
        }
        return s * z
    }

    override fun exp(x: Double): Double {
        var xr = x
        var hi = zero
        var lo = zero
        var k = 0
        val t: Double
        val c: Double
        val y: Double

        var hx = hi(xr) // high word of x
        val xsb = (hx ushr 31) and 1 // sign bit of x
        hx = hx and 0x7fffffff // high word of |x|

        // filter out non-finite argument
        if (hx >= 0x40862E42) { // if |x|>=709.78...
            if (hx >= 0x7ff00000) {
                return if (((hx and 0xfffff) or lo(xr)) != 0) {
                    xr + xr // NaN
                } else {
                    if (xsb == 0) xr else zero // exp(+-inf)={inf,0}
                }
            }
            if (xr > oThreshold) return huge * huge // overflow
            if (xr < uThreshold) return twom1000 * twom1000 // underflow
        }

        // argument reduction
        if (hx > 0x3fd62e42) { // if |x| > 0.5 ln2
            if (hx < 0x3FF0A2B2) { // and |x| < 1.5 ln2
                hi = if (xsb == 0) xr - ln2HI else xr + ln2HI // x-ln2HI[xsb]
                lo = if (xsb == 0) ln2LO else -ln2LO
                k = 1 - xsb - xsb
            } else {
                k = (ivln2 * xr + (if (xsb == 0) half else -half)).toInt() // C (int) truncates
                val tk = k.toDouble()
                hi = xr - tk * ln2HI // t*ln2HI is exact here
                lo = tk * ln2LO
            }
            xr = hi - lo
        } else if (hx < 0x3e300000) { // when |x|<2**-28
            if (huge + xr > one) return one + xr // trigger inexact
        } else {
            k = 0
        }

        // x is now in primary range
        t = xr * xr
        c = xr - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))
        if (k == 0) return one - ((xr * c) / (c - two) - xr)
        y = one - ((lo - (xr * c) / (two - c)) - hi)
        return if (k >= -1021) {
            withHi(y, hi(y) + (k shl 20)) // add k to y's exponent
        } else {
            withHi(y, hi(y) + ((k + 1000) shl 20)) * twom1000
        }
    }

    override fun ln(x: Double): Double {
        var xr = x
        val dk: Double
        var hx = hi(xr) // high word of x
        val lx = lo(xr) // low word of x

        var k = 0
        if (hx < 0x00100000) { // x < 2**-1022
            if (((hx and 0x7fffffff) or lx) == 0) return -two54 / zero // log(+-0)=-inf
            if (hx < 0) return (xr - xr) / zero // log(-#) = NaN
            k -= 54
            xr *= two54 // subnormal number, scale up x
            hx = hi(xr)
        }
        if (hx >= 0x7ff00000) return xr + xr
        k += (hx shr 20) - 1023
        hx = hx and 0x000fffff
        var i = (hx + 0x95f64) and 0x100000
        xr = withHi(xr, hx or (i xor 0x3ff00000)) // normalize x or x/2
        k += (i shr 20)
        val f = xr - one
        if ((0x000fffff and (2 + hx)) < 3) { // |f| < 2**-20
            if (f == zero) {
                if (k == 0) return zero
                dk = k.toDouble()
                return dk * ln2HI + dk * ln2LO
            }
            val rr = f * f * (half - third * f)
            if (k == 0) return f - rr
            dk = k.toDouble()
            return dk * ln2HI - ((rr - dk * ln2LO) - f)
        }
        val s = f / (two + f)
        dk = k.toDouble()
        val z = s * s
        i = hx - 0x6147a
        val w = z * z
        val j = 0x6b851 - hx
        val t1 = w * (Lg2 + w * (Lg4 + w * Lg6))
        val t2 = z * (Lg1 + w * (Lg3 + w * (Lg5 + w * Lg7)))
        i = i or j
        val rr = t2 + t1
        return if (i > 0) {
            val hfsq = half * f * f
            if (k == 0) {
                f - (hfsq - s * (hfsq + rr))
            } else {
                dk * ln2HI - ((hfsq - (s * (hfsq + rr) + dk * ln2LO)) - f)
            }
        } else {
            if (k == 0) {
                f - s * (f - rr)
            } else {
                dk * ln2HI - ((s * (f - rr) - dk * ln2LO) - f)
            }
        }
    }

    /** fdlibm 5.3 `s_scalbn.c`: `x * 2^n`, computed by exponent manipulation. */
    private fun scalbn(x: Double, n: Int): Double {
        var xr = x
        var hx = hi(xr)
        val lx = lo(xr)
        var k = (hx and 0x7ff00000) shr 20 // extract exponent
        if (k == 0) { // 0 or subnormal x
            if ((lx or (hx and 0x7fffffff)) == 0) return xr // +-0
            xr *= two54
            hx = hi(xr)
            k = ((hx and 0x7ff00000) shr 20) - 54
            if (n < -50000) return tiny * xr // underflow
        }
        if (k == 0x7ff) return xr + xr // NaN or Inf
        k += n
        if (k > 0x7fe) return huge * copysign(huge, xr) // overflow
        if (k > 0) return withHi(xr, (hx and 0x800fffff.toInt()) or (k shl 20)) // normal result
        if (k <= -54) {
            return if (n > 50000) {
                huge * copysign(huge, xr) // in case integer overflow in n+k
            } else {
                tiny * copysign(tiny, xr) // underflow
            }
        }
        k += 54 // subnormal result
        xr = withHi(xr, (hx and 0x800fffff.toInt()) or (k shl 20))
        return xr * twom54
    }

    private fun copysign(x: Double, y: Double): Double =
        withHi(x, (hi(x) and 0x7fffffff) or (hi(y) and Int.MIN_VALUE))
}

/** fdlibm `__HI(x)`: the high 32 bits of the IEEE representation. */
private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()

/** fdlibm `__LO(x)`: the low 32 bits of the IEEE representation. */
private fun lo(x: Double): Int = x.toRawBits().toInt()

/** A constant from fdlibm's two hex words (`hi`, `lo` are unsigned 32-bit values). */
private fun word(hi: Long, lo: Long): Double = Double.fromBits((hi shl 32) or lo)

private fun fromWords(hi: Int, lo: Int): Double =
    Double.fromBits((hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL))

/** `__HI(x) = hi`. */
private fun withHi(x: Double, hi: Int): Double = fromWords(hi, lo(x))

/** `__LO(x) = lo`. */
private fun withLo(x: Double, lo: Int): Double = fromWords(hi(x), lo)

/**
 * py-fsrs `round()` (round half to even) as specified in [kotobaya.contracts.fsrs.Scheduler]'s KDoc:
 * `f = floor(x); d = x − f` (exact); `d > 0.5` → `f + 1`; `d < 0.5` → `f`; `d == 0.5` → `f` if `f`
 * is even, else `f + 1`. Non-finite input is returned unchanged.
 */
internal fun roundHalfEven(x: Double): Double {
    if (x.isNaN() || x.isInfinite()) return x
    val f = floor(x)
    val d = x - f
    return when {
        d > 0.5 -> f + 1.0
        d < 0.5 -> f
        floor(f / 2.0) * 2.0 == f -> f
        else -> f + 1.0
    }
}
