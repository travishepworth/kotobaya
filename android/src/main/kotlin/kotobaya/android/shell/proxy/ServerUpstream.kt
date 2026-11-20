package kotobaya.android.shell.proxy

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotobaya.android.shell.api.ApiOperation
import kotobaya.android.shell.api.OpClass
import kotobaya.android.shell.api.Problem
import kotobaya.android.shell.api.Problems
import kotobaya.android.shell.api.ShellHeaders
import kotobaya.android.shell.api.ShellRequest
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.Upstream
import kotobaya.android.shell.api.percentDecode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * The real [Upstream] (SHELL-A5, LF-A2): forwards a `proxy` operation, the one forwarded request of rule L-9
 * (`createDeck` `type: list`) and the response cache's background fetches (rules K-2, K-3) to the server with the
 * device token, by SHELL.md rules P-1 … P-3 as amended by C-31.
 *
 * - P-1: [isOnline] false, no server URL, a connect/TLS failure or no complete answer within [callBudgetMs] →
 *   `503 shell_offline`; offline means no network attempt at all. No retry.
 * - P-2: forwarded at once: no push first, no cycle started or joined. `method`, `body`, `content-type`, `accept`,
 *   `x-request-id` and `Authorization: Bearer <token>` (the SPA's `authorization`, `cookie`, `x-kotobaya-csrf` and
 *   every other header are not sent). Redirects are followed here, without `Authorization`.
 * - P-3: answered as soon as the server's answer is complete. A `2xx` with `Kotobaya-Seq: n` above the pull cursor
 *   carries `kotobaya-shell-stale: 1` and [ProxySync.requestPull] schedules a pull (not awaited).
 *
 * The server's status and body pass through unchanged (401 and 403 included), with only the headers of SHELL.md 3.3:
 * `content-type`, `x-request-id`, `retry-after`, `content-location`, `kotobaya-seq`. For a `cached` operation (the
 * cache's own fetches) `If-None-Match` is sent and `etag` returned, for the response cache only. A body over 8 MiB or
 * not JSON / problem JSON / text → `415 shell_unsupported`. Nothing here logs, and the token is only ever put on the
 * first hop's request header; the stored pairing and the outbox are never touched.
 */
class ServerUpstream(
    /** `ShellInfo.server_url`: the server's API base, ending in `/api/v1`. */
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val isOnline: () -> Boolean,
    private val sync: ProxySync,
    private val http: OkHttpClient = defaultHttp(),
    private val callBudgetMs: Long = CALL_BUDGET_MS,
) : Upstream {
    override suspend fun forward(request: ShellRequest, operation: ApiOperation, requestId: String): ShellResponse {
        fun problem(p: Problem) = p.toResponse(request.path, requestId)

        upstreamPath(request) ?: return problem(Problems.notFound("No operation matches ${request.method} ${request.path}."))
        if (!isOnline()) return problem(Problems.shellOffline())
        val target = baseUrl()?.let { buildUrl(it, request) } ?: return problem(Problems.shellOffline())
        val bearer = token() ?: return problem(Problems.unauthenticated())
        val cached = operation.opClass == OpClass.CACHED

        val upstream = try {
            withTimeoutOrNull(callBudgetMs) { withContext(Dispatchers.IO) { exchange(target, request, requestId, bearer, cached) } }
        } catch (e: IOException) {
            null
        } ?: return problem(Problems.shellOffline())

        val response = when (upstream) {
            is Exchange.Unsupported -> return problem(Problems.shellUnsupported(upstream.detail))
            is Exchange.Answer -> upstream.response
        }
        val seq = response.headers[SEQ]?.trim()?.toLongOrNull()
        if (response.status !in 200..299 || seq == null || sync.pullCursor() >= seq) return response
        sync.requestPull(seq)
        return response.copy(headers = response.headers + (STALE to "1"))
    }

    private sealed interface Exchange {
        data class Answer(val response: ShellResponse) : Exchange

        data class Unsupported(val detail: String) : Exchange
    }

    /** The request (and its redirects) within one budget; throws [IOException] for P-1 failures. */
    private suspend fun exchange(first: HttpUrl, request: ShellRequest, requestId: String, bearer: String, cached: Boolean): Exchange {
        var url = first
        var method = request.method
        var body: String? = request.body
        var withAuth = true
        repeat(MAX_REDIRECTS + 1) {
            val builder = Request.Builder().url(url)
            if (withAuth) builder.header("Authorization", "Bearer $bearer")
            request.header("accept")?.let { builder.header("Accept", it) }
            if (cached) request.header(IF_NONE_MATCH)?.let { builder.header("If-None-Match", it) }
            builder.header("X-Request-Id", requestId)
            val contentType = request.header(ShellHeaders.CONTENT_TYPE)
            val requestBody = when {
                body != null -> body.toRequestBody(contentType?.toMediaTypeOrNull())
                method == "GET" || method == "HEAD" || method == "DELETE" -> null
                else -> ByteArray(0).toRequestBody(null)
            }
            builder.method(method, requestBody)
            http.newCall(builder.build()).await().use { resp ->
                if (resp.isRedirect) {
                    val next = resp.header("Location")?.let { url.resolve(it) } ?: throw IOException("redirect without a usable location")
                    if (next.scheme != "https" && next.scheme != "http") throw IOException("redirect to an unsupported scheme")
                    val code = resp.code
                    if (code == 303 || ((code == 301 || code == 302) && method != "GET" && method != "HEAD")) {
                        method = "GET"
                        body = null
                    }
                    url = next
                    withAuth = false // P-2: redirects are followed without the Authorization header
                    return@repeat
                }
                return read(resp, requestId, cached)
            }
        }
        throw IOException("too many redirects")
    }

    private fun read(resp: Response, requestId: String, cached: Boolean): Exchange {
        val source = resp.body?.source()
        val buffer = Buffer()
        if (source != null) {
            while (source.read(buffer, CHUNK) != -1L) {
                if (buffer.size > MAX_RESPONSE_BYTES) return Exchange.Unsupported("The server's answer is larger than 8 MiB.")
            }
        }
        val type = resp.header("Content-Type")
        val media = type?.toMediaTypeOrNull()
        val text: String? = if (buffer.size == 0L) {
            null
        } else {
            if (media == null || !isText(media)) return Exchange.Unsupported("The server's answer is not text.")
            buffer.readString(media.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        }
        val headers = LinkedHashMap<String, String>()
        if (text != null && type != null) headers[ShellHeaders.CONTENT_TYPE] = type
        headers[ShellHeaders.REQUEST_ID] = resp.header("X-Request-Id") ?: requestId
        resp.header("Retry-After")?.let { headers[ShellHeaders.RETRY_AFTER] = it }
        resp.header("Content-Location")?.let { headers[ShellHeaders.CONTENT_LOCATION] = it }
        resp.header("Kotobaya-Seq")?.let { headers[SEQ] = it }
        if (cached) resp.header("ETag")?.let { headers[ETAG] = it }
        return Exchange.Answer(ShellResponse(resp.code, headers, text))
    }

    /** [ShellRequest.path] without `/api/v1`, or null when it is not forwardable (dot, empty or slash-hiding segments). */
    private fun upstreamPath(request: ShellRequest): String? {
        val path = request.path
        if (!path.startsWith(API_PREFIX + "/")) return null
        val rest = path.removePrefix(API_PREFIX)
        if (rest.any { it == '?' || it == '#' || it == '\\' || it.isWhitespace() || it.isISOControl() }) return null
        for (segment in rest.substring(1).split('/')) {
            val decoded = percentDecode(segment)
            if (segment.isEmpty() || decoded == "." || decoded == ".." || '/' in decoded || '\\' in decoded) return null
        }
        return rest
    }

    /** `server_url` + path without `/api/v1` + query; null when the base is not a usable URL or the result left it. */
    private fun buildUrl(base: String, request: ShellRequest): HttpUrl? {
        val rest = upstreamPath(request) ?: return null
        val root = base.trimEnd('/')
        val parsed = (root + rest).toHttpUrlOrNull() ?: return null
        val rootUrl = root.toHttpUrlOrNull() ?: return null
        if (parsed.host != rootUrl.host || parsed.port != rootUrl.port || parsed.scheme != rootUrl.scheme) return null
        if (!parsed.encodedPath.startsWith(rootUrl.encodedPath.trimEnd('/') + "/")) return null
        val builder = parsed.newBuilder().query(null)
        for ((name, values) in request.query) for (v in values) builder.addQueryParameter(name, v)
        return builder.build()
    }

    companion object {
        const val CALL_BUDGET_MS: Long = 12_000
        const val MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024
        const val MAX_REDIRECTS: Int = 5
        const val SEQ: String = "kotobaya-seq"
        const val STALE: String = "kotobaya-shell-stale"

        /** Request header of a response-cache revalidation (`cached` operations only). */
        const val IF_NONE_MATCH: String = "if-none-match"

        /** Response header returned for `cached` operations only (stored by the response cache, never sent to the SPA). */
        const val ETAG: String = "etag"
        private const val API_PREFIX = "/api/v1"
        private const val CHUNK = 64L * 1024

        /** Redirects are followed by [ServerUpstream] itself (P-2); the overall 12 s budget is the coroutine's. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(CALL_BUDGET_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(CALL_BUDGET_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_BUDGET_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        internal fun isText(m: MediaType): Boolean {
            val t = m.type.lowercase()
            val s = m.subtype.lowercase()
            return t == "text" || (t == "application" && (s == "json" || s == "problem+json"))
        }
    }
}

/** [Call.enqueue] as a cancellable suspension: cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        },
    )
}
