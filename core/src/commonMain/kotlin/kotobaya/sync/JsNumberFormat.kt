package kotobaya.sync

/**
 * ECMAScript `Number::toString(10)` for finite doubles (= what `JSON.stringify` writes), in pure
 * common Kotlin so jvm, android and js print identical text. `Double.toString` is not used: the JVM's
 * differs from JavaScript's in exponent format and (before JDK 19) in digit choice.
 *
 * Digit choice (ECMA-262 Number::toString step 5): the fewest significant digits `k` such that the
 * decimal parses back to the same double; among the (at most two) `k`-digit candidates that do,
 * the one closest to the exact value, ties to the even digit. The exact decimal expansion of the
 * double is computed with a small base-1e9 bignum, and each candidate is checked with
 * `String.toDouble()`, which is correctly rounded on every platform.
 */
internal object JsNumberFormat {
    private const val TWO_POW_53 = 9007199254740992.0

    fun format(d: Double): String {
        require(d.isFinite()) { "not a JSON number: $d" }
        if (d == 0.0) return "0"
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < TWO_POW_53) return d.toLong().toString()
        val negative = d < 0
        val a = if (negative) -d else d
        val (digits, n) = shortest(a)
        val body = layout(digits, n)
        return if (negative) "-$body" else body
    }

    /** ECMA-262 Number::toString steps 6-10: value = 0.[digits] * 10^n. */
    private fun layout(s: String, n: Int): String {
        val k = s.length
        return when {
            n in k..21 -> s + "0".repeat(n - k)
            n in 1..21 -> s.substring(0, n) + "." + s.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + s
            else -> {
                val e = n - 1
                val sign = if (e < 0) "-" else "+"
                val mant = if (k == 1) s else s.substring(0, 1) + "." + s.substring(1)
                "${mant}e$sign${if (e < 0) -e else e}"
            }
        }
    }

    /** Shortest round-tripping digits of the positive finite [a] and their exponent `n` (value = 0.digits * 10^n). */
    private fun shortest(a: Double): Pair<String, Int> {
        val (all, n) = exactDecimal(a)
        for (p in 1..17) {
            if (all.length <= p) return all to n
            val down = all.substring(0, p).trimEnd('0')
            val (upDigits, upN) = increment(all.substring(0, p), n)
            val downOk = down.isNotEmpty() && parse(down, n) == a
            val upOk = parse(upDigits, upN) == a
            if (!downOk && !upOk) continue
            if (downOk && !upOk) return down to n
            if (upOk && !downOk) return upDigits to upN
            // both round-trip: the closer one; the remainder 0.R decides (trailing zeros are stripped)
            val rest = all.substring(p)
            val c = compareHalf(rest)
            return when {
                c < 0 -> down to n
                c > 0 -> upDigits to upN
                (all[p - 1] - '0') % 2 == 0 -> down to n
                else -> upDigits to upN
            }
        }
        error("no 17-digit representation round-trips: $a")
    }

    /** Compares the fraction `0.[rest]` with one half. [rest] has no trailing zeros and is non-empty. */
    private fun compareHalf(rest: String): Int = when {
        rest[0] > '5' -> 1
        rest[0] < '5' -> -1
        rest.length > 1 -> 1
        else -> 0
    }

    /** `0.[digits] * 10^n` plus one unit in the last digit, trailing zeros stripped. */
    private fun increment(digits: String, n: Int): Pair<String, Int> {
        val chars = digits.toCharArray()
        var i = chars.size - 1
        while (i >= 0 && chars[i] == '9') {
            chars[i] = '0'
            i--
        }
        if (i < 0) return "1" to n + 1
        chars[i] = chars[i] + 1
        return chars.concatToString().trimEnd('0') to n
    }

    private fun parse(digits: String, n: Int): Double = "0.${digits}e$n".toDouble()

    /**
     * The exact decimal expansion of the positive finite [a]: its significant digits (no leading or
     * trailing zeros) and `n` with value = 0.digits * 10^n.
     */
    private fun exactDecimal(a: Double): Pair<String, Int> {
        val bits = a.toRawBits()
        val biased = ((bits ushr 52) and 0x7ff).toInt()
        val fraction = bits and 0xfffffffffffffL
        val mantissa = if (biased == 0) fraction else fraction or (1L shl 52)
        val e2 = (if (biased == 0) 1 else biased) - 1075 // a = mantissa * 2^e2
        val big = BigDec.of(mantissa)
        val decimalShift: Int
        if (e2 >= 0) {
            var left = e2
            while (left > 0) {
                val s = minOf(left, 28)
                big.mulSmall(1L shl s)
                left -= s
            }
            decimalShift = 0
        } else {
            // mantissa * 2^e2 = mantissa * 5^-e2 * 10^e2
            var left = -e2
            while (left > 0) {
                val s = minOf(left, 13)
                var f = 1L
                repeat(s) { f *= 5 }
                big.mulSmall(f)
                left -= s
            }
            decimalShift = e2
        }
        val text = big.toDecimalString()
        val trimmed = text.trimEnd('0')
        // value = text * 10^decimalShift = 0.text * 10^(len + decimalShift)
        return trimmed to text.length + decimalShift
    }

    /** Minimal unsigned bignum, little-endian base-1e9 limbs. */
    private class BigDec private constructor(private var limbs: LongArray, private var size: Int) {
        fun mulSmall(f: Long) {
            var carry = 0L
            for (i in 0 until size) {
                val v = limbs[i] * f + carry
                limbs[i] = v % BASE
                carry = v / BASE
            }
            while (carry > 0) {
                if (size == limbs.size) limbs = limbs.copyOf(limbs.size * 2)
                limbs[size++] = carry % BASE
                carry /= BASE
            }
        }

        fun toDecimalString(): String {
            val sb = StringBuilder()
            sb.append(limbs[size - 1].toString())
            for (i in size - 2 downTo 0) sb.append(limbs[i].toString().padStart(9, '0'))
            return sb.toString()
        }

        companion object {
            const val BASE = 1_000_000_000L

            fun of(v: Long): BigDec {
                val arr = LongArray(8)
                var x = v
                var n = 0
                while (x > 0) {
                    arr[n++] = x % BASE
                    x /= BASE
                }
                return BigDec(arr, n)
            }
        }
    }
}
