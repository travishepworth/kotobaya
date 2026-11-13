package kotobaya.sync.client.store

import kotobaya.contracts.InstantMs
import kotobaya.contracts.Uuid
import kotobaya.contracts.sync.Op
import kotlin.random.Random

/** Why an op is being applied. */
public enum class ApplyOrigin {
    /** The local effect of an op this device just minted (SYNC.md 4 step 2). */
    LOCAL,

    /** An op from a pull page (SYNC.md 6.1 step 1), including this device's own ops coming back. */
    PULLED,
}

/**
 * Applies one op to the learning tables (SYNC.md 7–9). Implemented by T303b; the same function is
 * used for the local effect of a minted op and for pulled ops (SYNC.md 4 step 2).
 *
 * Contract: called inside the caller's transaction; MUST be idempotent (SYNC.md 7.8: applying an op
 * twice changes nothing); an op whose target is missing is a no-op, not an error (7.7). Throwing
 * aborts the whole transaction (the mint is refused / the page is not committed and is retried).
 * It never calls `observe` or touches `sync_state.hlc_last`: the caller does.
 */
public fun interface OpApplier<L> {
    /** Applies [op] to `tx.learning`. */
    public fun apply(tx: StoreTransaction<L>, op: Op, origin: ApplyOrigin)

    /**
     * Runs [block], a run of [apply] calls on [tx] with nothing else writing `tx.learning` in
     * between, and returns its result; the implementation may defer per-op work that only the final
     * state needs to the end of [block], still inside [tx] (BUG-8: a pull chunk). The rows committed
     * MUST be the ones the same [apply] calls give one by one (up to what SYNC.md 11 does not
     * compare). Default: just [block].
     */
    public fun <R> batch(tx: StoreTransaction<L>, block: () -> R): R = block()
}

/** The device's physical clock `pt` (SYNC.md 3.2), epoch ms UTC. */
public fun interface Clock {
    /** Current wall-clock time. */
    public fun nowMs(): InstantMs
}

/** Source of new `op_id`s (SYNC.md 2: UUIDv7, lowercase, minted with the op). */
public fun interface OpIdSource {
    /** A new id; [nowMs] is the device clock at mint time. */
    public fun newOpId(nowMs: InstantMs): Uuid
}

/** UUIDv7 (RFC 9562) from [random]: 48-bit ms, version 7, variant `10`, 74 random bits. */
public class UuidV7Source(private val random: Random) : OpIdSource {
    override fun newOpId(nowMs: InstantMs): Uuid = format(nowMs, random.nextBytes(10))

    /** Pure helpers. */
    public companion object {
        private const val HEX = "0123456789abcdef"

        /** The UUIDv7 with timestamp [ms] (clamped to 48 bits) and the 10 [randomBytes]. */
        public fun format(ms: InstantMs, randomBytes: ByteArray): Uuid {
            require(randomBytes.size == 10) { "need 10 random bytes" }
            val b = ByteArray(16)
            val t = ms.coerceIn(0L, 0xffffffffffffL)
            for (i in 0 until 6) b[i] = (t ushr (8 * (5 - i))).toByte()
            for (i in 0 until 10) b[6 + i] = randomBytes[i]
            b[6] = ((b[6].toInt() and 0x0f) or 0x70).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val sb = StringBuilder(36)
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
                val v = b[i].toInt() and 0xff
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return sb.toString()
        }
    }
}
