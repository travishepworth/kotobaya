package kotobaya.android.ankiconnect

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HttpReply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")

/**
 * The HTTP rules of the server, apart from the socket: loopback `Host` only (DNS-rebinding guard),
 * `Origin` must match [allowedOrigins] (an absent Origin is a non-browser client and passes), CORS headers,
 * preflight, and POST bodies to [protocol]. The API key is the protocol layer's.
 */
class AnkiConnectHttp(
    private val protocol: AnkiConnectProtocol,
    private val allowedOrigins: () -> List<String>,
) {
    /** [headers] keys must be lower-case. */
    fun handle(method: String, headers: Map<String, String>, body: String): HttpReply {
        headers["host"]?.let { if (!isLoopbackHost(it)) return HttpReply(403, body = "forbidden host") }
        val origin = headers["origin"]
        val cors = LinkedHashMap<String, String>()
        if (origin != null) {
            if (!originAllowed(origin, allowedOrigins())) return HttpReply(403, body = "origin not allowed")
            cors["Access-Control-Allow-Origin"] = origin
            cors["Vary"] = "Origin"
        }
        return when (method.uppercase()) {
            "OPTIONS" -> HttpReply(
                204,
                cors + mapOf(
                    "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
                    "Access-Control-Allow-Headers" to (headers["access-control-request-headers"] ?: "content-type"),
                    "Access-Control-Allow-Private-Network" to "true",
                    "Access-Control-Max-Age" to "600",
                ),
            )
            "GET" -> HttpReply(200, cors + ("Content-Type" to "text/plain; charset=utf-8"), "AnkiConnect v.${AnkiConnectProtocol.VERSION}")
            "POST" -> HttpReply(200, cors + ("Content-Type" to "application/json; charset=utf-8"), protocol.handle(body))
            else -> HttpReply(405, cors, "method not allowed")
        }
    }

    companion object {
        fun isLoopbackHost(hostHeader: String): Boolean {
            val h = if (hostHeader.startsWith("[")) hostHeader.substringBefore(']') + "]" else hostHeader.substringBefore(':')
            return h == "127.0.0.1" || h.equals("localhost", ignoreCase = true) || h == "[::1]"
        }

        /** Exact match, a lone star (anything), or a trailing-star prefix like moz-extension:// followed by a star. */
        fun originAllowed(origin: String, patterns: List<String>): Boolean = patterns.any { p ->
            val pat = p.trim()
            when {
                pat.isEmpty() -> false
                pat == "*" -> true
                pat.endsWith("*") -> origin.startsWith(pat.dropLast(1))
                else -> origin == pat
            }
        }
    }
}

/**
 * A minimal HTTP/1.1 server (one request per connection) bound to **127.0.0.1 only**: never a wildcard or
 * LAN address. No dependency; the AnkiConnect traffic is a few small POSTs.
 */
class AnkiConnectServer(private val http: AnkiConnectHttp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null

    /** The address the socket is bound to; null when stopped. */
    val boundAddress: InetSocketAddress? get() = socket?.localSocketAddress as? InetSocketAddress

    /** Binds [port] (0 = any free port) on loopback; returns the port. Throws [IOException] if it is taken. */
    @Synchronized
    fun start(port: Int): Int {
        stop()
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(LOOPBACK, port), BACKLOG)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        socket = s
        scope.launch {
            while (isActive && !s.isClosed) {
                val c = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                launch { c.use(::serve) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private fun serve(c: Socket) {
        c.soTimeout = TIMEOUT_MS
        val reply = try {
            val input = BufferedInputStream(c.getInputStream())
            val requestLine = readLine(input) ?: return
            val method = requestLine.substringBefore(' ')
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':', "").trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) {
                HttpReply(413, body = "body too large")
            } else {
                val body = ByteArray(length).also { readFully(input, it) }
                http.handle(method, headers, String(body, Charsets.UTF_8))
            }
        } catch (_: IOException) {
            return
        } catch (_: Exception) {
            HttpReply(500, body = "internal error")
        }
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${reply.status} ${reason(reply.status)}\r\n")
        reply.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        c.getOutputStream().apply {
            write(head.toString().toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
            out.write(b)
            if (out.size() > MAX_LINE) throw IOException("line too long")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("short body")
            off += n
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        403 -> "Forbidden"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    companion object {
        /** 127.0.0.1 itself, not `InetAddress.getLoopbackAddress()` (which may be ::1). */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private const val BACKLOG = 16
        private const val TIMEOUT_MS = 15_000
        private const val MAX_BODY = 32 * 1024 * 1024
        private const val MAX_LINE = 16 * 1024
    }
}
