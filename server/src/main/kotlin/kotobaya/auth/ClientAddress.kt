package kotobaya.auth

import java.net.Inet6Address
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

/**
 * Parses an IP literal without ever resolving a host name (no `InetAddress.getByName`); null if [s] is not one.
 * Strips `[...]` brackets. Accepts dotted-quad IPv4 and RFC 4291 IPv6 text (with `::` and a trailing dotted quad);
 * rejects zone ids, ports and everything else.
 */
fun parseIpLiteral(s: String): InetAddress? {
    val t = s.trim().removeSurrounding("[", "]")
    if (t.isEmpty()) return null
    val bytes = parseIpv4(t) ?: if (':' in t) parseIpv6(t) else null
    return bytes?.let { InetAddress.getByAddress(it) }
}

private fun parseIpv4(s: String): ByteArray? {
    if (!IPV4.matches(s)) return null
    return s.split('.').map { it.toInt().toByte() }.toByteArray()
}

private fun parseIpv6(s: String): ByteArray? {
    if (s.length > 45) return null
    var t = s
    var tail4: ByteArray? = null
    val lastColon = t.lastIndexOf(':')
    if ('.' in t.substring(lastColon + 1)) {
        tail4 = parseIpv4(t.substring(lastColon + 1)) ?: return null
        t = t.substring(0, lastColon + 1) + "0:0"
    }
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { g ->
            if (g.length !in 1..4 || !g.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
            g.toInt(16)
        }
    }
    val dbl = t.indexOf("::")
    val all: List<Int> = if (dbl >= 0) {
        if (t.indexOf("::", dbl + 1) >= 0 || t.indexOf(":::") >= 0) return null
        val head = groups(t.substring(0, dbl)) ?: return null
        val tail = groups(t.substring(dbl + 2)) ?: return null
        if (head.size + tail.size > 7) return null
        head + List(8 - head.size - tail.size) { 0 } + tail
    } else {
        groups(t)?.takeIf { it.size == 8 } ?: return null
    }
    val out = ByteArray(16)
    for ((i, g) in all.withIndex()) {
        out[2 * i] = (g shr 8).toByte()
        out[2 * i + 1] = g.toByte()
    }
    tail4?.copyInto(out, 12)
    return out
}

/**
 * The client address the login and pairing rate limits key on.
 *
 * If the TCP peer is inside [trustedProxies], `X-Forwarded-For` is walked from the right: each hop is parsed only
 * until the first one that is not itself a trusted proxy, and that hop is the client (hops left of it are
 * client-supplied and never parsed). If every hop is trusted, the leftmost hop. A peer outside the trusted set, an
 * absent header, or a malformed hop met during the walk: the peer.
 *
 * IPv6 clients are keyed on their /64 (one host usually owns a whole /64, so per-address keys would be free to rotate).
 */
class ClientAddressResolver(private val trustedProxies: List<Cidr>) {
    fun resolve(peer: String, forwardedFor: List<String>): String {
        val peerAddr = parseIpLiteral(peer) ?: return peer
        if (!trusted(peerAddr)) return key(peerAddr)
        val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }
        if (hops.all { it.isEmpty() }) return key(peerAddr)
        var leftmost: InetAddress = peerAddr
        for (h in hops.asReversed()) {
            val a = parseIpLiteral(h) ?: return key(peerAddr)
            if (!trusted(a)) return key(a)
            leftmost = a
        }
        return key(leftmost)
    }

    private fun trusted(a: InetAddress) = trustedProxies.any { it.contains(a) }

    companion object {
        /** The rate-limit key: the IPv4 address, or the IPv6 /64 as `<prefix>/64`. */
        fun key(a: InetAddress): String {
            if (a !is Inet6Address) return a.hostAddress
            val prefix = a.address.copyOf().also { it.fill(0, 8, 16) }
            return InetAddress.getByAddress(prefix).hostAddress + "/64"
        }

        fun parseList(s: String?): List<Cidr> =
            s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(Cidr::parse) ?: emptyList()
    }
}
