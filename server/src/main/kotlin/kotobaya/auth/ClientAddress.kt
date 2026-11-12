package kotobaya.auth

import java.net.InetAddress

/** One CIDR block (IPv4 or IPv6). An IPv4 block never matches an IPv6 address and vice versa. */
class Cidr private constructor(private val network: ByteArray, private val prefix: Int) {
    fun contains(address: InetAddress): Boolean {
        val a = address.address
        if (a.size != network.size) return false
        var bits = prefix
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    companion object {
        /** `10.42.0.0/16`, `fd00::/8`, or a bare address (= /32 or /128). */
        fun parse(s: String): Cidr {
            val t = s.trim()
            val addr = parseIpLiteral(t.substringBefore('/')) ?: throw IllegalArgumentException("not an IP address: $t")
            val max = addr.address.size * 8
            val prefix = if ('/' in t) {
                t.substringAfter('/').toIntOrNull()?.takeIf { it in 0..max } ?: throw IllegalArgumentException("bad prefix: $t")
            } else {
                max
            }
            return Cidr(addr.address, prefix)
        }
    }
}

private val IPV4 = Regex("^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$")
private val IPV6_CHARS = Regex("^[0-9A-Fa-f:.]+$")

/** Parses an IP literal without ever resolving a host name; null if [s] is not one. Strips `[...]` brackets. */
fun parseIpLiteral(s: String): InetAddress? {
    val t = s.trim().removeSurrounding("[", "]")
    if (t.isEmpty()) return null
    val literal = when {
        IPV4.matches(t) -> true
        ':' in t && IPV6_CHARS.matches(t) -> true
        else -> false
    }
    if (!literal) return null
    return runCatching { InetAddress.getByName(t) }.getOrNull()
}

/**
 * The client address the login rate limit keys on.
 *
 * If the TCP peer is inside [trustedProxies], the client is the rightmost `X-Forwarded-For` hop that is not
 * itself a trusted proxy (hops left of it are client-supplied and ignored). If every hop is trusted, the
 * leftmost hop. A peer outside the trusted set, an absent header, or a header with any malformed hop: the peer.
 */
class ClientAddressResolver(private val trustedProxies: List<Cidr>) {
    fun resolve(peer: String, forwardedFor: List<String>): String {
        val peerAddr = parseIpLiteral(peer) ?: return peer
        if (trustedProxies.none { it.contains(peerAddr) }) return normalise(peerAddr)
        val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }
        if (hops.isEmpty() || hops.all { it.isEmpty() }) return normalise(peerAddr)
        val parsed = hops.map { parseIpLiteral(it) ?: return normalise(peerAddr) }
        val client = parsed.lastOrNull { a -> trustedProxies.none { it.contains(a) } } ?: parsed.first()
        return normalise(client)
    }

    private fun normalise(a: InetAddress): String = a.hostAddress

    companion object {
        fun parseList(s: String?): List<Cidr> =
            s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(Cidr::parse) ?: emptyList()
    }
}
