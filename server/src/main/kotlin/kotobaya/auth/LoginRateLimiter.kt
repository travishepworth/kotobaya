package kotobaya.auth

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * Failed-login limiter, in memory (contracts/api/SUMMARY.md "Known gaps": no table; a restart resets it).
 *
 * Sliding window of [window]. Once an address has [perAddress] failures inside the window, every further
 * attempt from it (even with correct credentials) is refused until the oldest of those failures leaves the
 * window. [global] does the same across all addresses, against distributed guessing. A successful login
 * clears the address's failures.
 */
class LoginRateLimiter(
    private val perAddress: Int = 5,
    private val global: Int = 100,
    private val window: Duration = Duration.ofMinutes(15),
    private val maxTrackedAddresses: Int = 10_000,
) {
    private val byAddress = HashMap<String, ArrayDeque<Instant>>()
    private val all = ArrayDeque<Instant>()

    /**
     * Atomically checks [address] and, when allowed, records this attempt as a failure up front (so concurrent
     * attempts cannot slip past the limit). Returns seconds to wait (attempt refused, nothing recorded) or null
     * (attempt allowed). Follow up with [succeeded] or [uncounted]; otherwise the failure stands.
     */
    @Synchronized
    fun begin(address: String, now: Instant): Long? {
        prune(all, now)
        val mine = byAddress[address]?.also { prune(it, now) }
        val waits = listOfNotNull(
            mine?.takeIf { it.size >= perAddress }?.let { secondsUntilFree(it, it.size - perAddress, now) },
            all.takeIf { it.size >= global }?.let { secondsUntilFree(it, it.size - global, now) },
        )
        if (waits.isNotEmpty()) return waits.max()
        if (byAddress.size >= maxTrackedAddresses && address !in byAddress) {
            byAddress.values.forEach { prune(it, now) }
            byAddress.values.removeIf { it.isEmpty() }
            // Still full: forget per-address history rather than grow without bound; the global limit still holds.
            if (byAddress.size >= maxTrackedAddresses) byAddress.clear()
        }
        byAddress.getOrPut(address) { ArrayDeque() }.addLast(now)
        all.addLast(now)
        return null
    }

    /** The attempt begun at [at] succeeded: clear the address's failures and drop the attempt from the global count. */
    @Synchronized
    fun succeeded(address: String, at: Instant) {
        byAddress.remove(address)
        all.removeLastOccurrence(at)
    }

    /** The attempt begun at [at] was not a guess (e.g. `totp_required`): forget it. */
    @Synchronized
    fun uncounted(address: String, at: Instant) {
        byAddress[address]?.let { q ->
            q.removeLastOccurrence(at)
            if (q.isEmpty()) byAddress.remove(address)
        }
        all.removeLastOccurrence(at)
    }

    private fun prune(q: ArrayDeque<Instant>, now: Instant) {
        val cutoff = now.minus(window)
        while (q.isNotEmpty() && !q.peekFirst().isAfter(cutoff)) q.removeFirst()
    }

    /** The queue holds failures oldest first; the attempt is free once the failure at [index] leaves the window. */
    private fun secondsUntilFree(q: ArrayDeque<Instant>, index: Int, now: Instant): Long {
        val t = q.elementAt(index)
        val ms = Duration.between(now, t.plus(window)).toMillis()
        return maxOf(1L, (ms + 999) / 1000)
    }
}
