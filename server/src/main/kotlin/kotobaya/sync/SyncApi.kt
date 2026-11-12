package kotobaya.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotobaya.auth.authenticated
import kotobaya.auth.requireDevice
import kotobaya.server.ApiException
import kotobaya.server.Database
import kotobaya.server.JsonUtf8
import kotobaya.server.OperationHandler
import kotobaya.server.Problems
import kotobaya.server.respondJson
import io.ktor.server.response.respondText
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The sync module: the op-apply entry point ([opApply]) and the `syncPush` / `syncPull` handlers.
 *
 * Wiring (server `Main.kt`, not this ticket's path): build one `SyncModule(db, SyncConfig(events =
 * SyncEvents { dsl, hw, _ -> jobs.bus.syncChangedInTx(dsl, hw) }))`, add [handlers] to
 * `AppDeps.handlers`, and hand [opApply] to every module that writes learning data.
 */
class SyncModule(db: Database, config: SyncConfig = SyncConfig()) {
    val opApply = OpApply(db, config)
    val push = PushService(opApply)
    val pull = PullService(db, config)

    val handlers: Map<String, OperationHandler> = mapOf(
        "syncPush" to { handlePush(this@SyncModule) },
        "syncPull" to { handlePull(this@SyncModule) },
    )
}

private fun payloadTooLarge(max: Int) = ApiException(
    HttpStatusCode.PayloadTooLarge, "payload_too_large", "Payload too large",
    "The body is larger than limits.max_push_bytes ($max); push fewer ops at a time.",
)

private fun unprocessable(pointer: String, detail: String) = OpInputInvalid(pointer, detail).toApiException()

private suspend fun ApplicationCall.handlePush(m: SyncModule) {
    val auth = authenticated ?: throw Problems.unauthenticated("No credential.")
    auth.requireDevice()
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Unsupported media type", "Send application/json.")
    }
    val max = m.opApply.config.maxPushBytes
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > max) throw payloadTooLarge(max) }
    val bytes = receiveChannel().readRemaining(max.toLong() + 1).readByteArray()
    if (bytes.size > max) throw payloadTooLarge(max)
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        throw Problems.invalidRequest("The body is not valid UTF-8.")
    }
    // A collision without a merge policy is already a 503 ApiException (OpApply maps it on every path).
    val outcome = withContext(Dispatchers.IO) { m.push.push(text, auth.principal) }
    when (outcome) {
        is PushOutcome.BadRequest -> throw Problems.invalidRequest(outcome.message)
        is PushOutcome.Ok -> respondJson(outcome.body)
    }
}

private suspend fun ApplicationCall.handlePull(m: SyncModule) {
    val auth = authenticated ?: throw Problems.unauthenticated("No credential.")
    val since = request.queryParameters["since"]?.let { s ->
        s.toLongOrNull()?.takeIf { it >= 0 } ?: throw unprocessable("/since", "since must be an integer >= 0.")
    } ?: 0L
    val limit = request.queryParameters["limit"]?.let { s ->
        s.toIntOrNull()?.takeIf { it in 1..PullService.MAX_LIMIT } ?: throw unprocessable("/limit", "limit must be an integer from 1 to ${PullService.MAX_LIMIT}.")
    } ?: PullService.DEFAULT_LIMIT
    val page = withContext(Dispatchers.IO) { m.pull.pull(since, limit, auth.principal.deviceId) }
    respondText(page.text, JsonUtf8)
}
