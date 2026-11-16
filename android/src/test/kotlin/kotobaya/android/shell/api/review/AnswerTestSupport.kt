package kotobaya.android.shell.api.review

import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.identity.IdentityTestSupport.minted
import kotobaya.android.shell.api.review.routes as reviewRoutes
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row

/**
 * Helpers of the SHELL-L3 tests: [ParityDevice]s over the review routes, seeded with the two notes and their
 * `meaning` cards of the scenario `answer-retry-same-id` (created by device A one hour before [T0]).
 */
internal object AnswerTestSupport {
    const val T0 = 1_795_168_800_000L
    const val X = "01931f3e-0000-7000-8000-0000000000d4"
    const val Y = "01931f3e-0000-7000-8000-0000000000d5"
    const val Z = "01931f3e-0000-7000-8000-0000000000d6"
    const val B = "01931f3e-0000-7000-8000-0000000000b2"
    const val C1 = "0193a1b2-0002-7000-8000-000000000010"
    const val C2 = "0193a1b2-0002-7000-8000-000000000020"
    const val HOUR = 3_600_000L

    fun seed(): List<JsonObject> = ParityScenario.load("answer-retry-same-id").seedLog

    fun device(id: String = X, seed: List<JsonObject> = seed(), startMs: Long = T0): ParityDevice =
        ParityDevice(id, startMs, reviewRoutes).also { if (seed.isNotEmpty()) it.pull(seed, "seed") }

    /** A review id that is the same on every device of a test: `n` names the answer. */
    fun reviewId(n: Int): String = "0193b000-0003-7000-8000-%012x".format(n)

    /** `POST /review/answer` at device time [at] (null: the clock as it is). [extra]: more members, `"k":v,…`. */
    fun ParityDevice.answer(id: String, cardId: String, rating: Int = 3, at: Long? = null, extra: String = ""): ShellResponse {
        at?.let { clock.now = it }
        val more = if (extra.isEmpty()) "" else ",$extra"
        return request("POST", "/api/v1/review/answer", "", ParityJson.parse("""{"id":"$id","card_id":"$cardId","rating":$rating$more}"""))
    }

    fun ParityDevice.undo(reviewId: String, at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request("POST", "/api/v1/review/undo", "", ParityJson.parse("""{"review_id":"$reviewId"}"""))
    }

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    /** `status pointer code` of a problem (`errors[0]`), or `status code` when it has no `errors`. */
    fun ShellResponse.problem(): String {
        val b = json()
        val first = (b.members["errors"] as? kotobaya.contracts.sync.JsonArray)?.items?.firstOrNull()?.let(ParityJson::obj)
        return if (first != null) {
            "$status ${ParityJson.str(first.members["pointer"])} ${ParityJson.str(first.members["code"])}"
        } else {
            "$status ${ParityJson.str(b.members["code"])}"
        }
    }

    fun ParityDevice.reviews(): List<Row> = store.read { it.learning.all(LearningTable.REVIEW_LOG) }.sortedBy { ParityJson.str(it["id"]) }

    fun ParityDevice.cards(): List<Row> = store.read { it.learning.all(LearningTable.CARD) }.sortedBy { ParityJson.str(it["id"]) }

    fun ParityDevice.card(id: String): Row = cards().single { ParityJson.str(it["id"]) == id }

    fun ParityDevice.mintedTypes(): List<String> = minted().map { ParityJson.str(it.members["op_type"]) }

    /** An API `Timestamp` of [ms] (a `reviewed_at` request value). */
    fun iso(ms: Long): String = kotobaya.android.shell.api.identity.timestamp(ms)

    /** The HLC of [device] at wall [wallMs] with [counter]. */
    fun hlc(wallMs: Long, device: String, counter: Int = 0): String = "%012x-%04x-%s".format(wallMs, counter, device)

    /** A `review.logged` of another replica in pushed form; `reviewed_at` and the HLC's wall time are independent. */
    fun remoteReview(
        id: String,
        cardId: String,
        reviewedAt: Long,
        hlcWall: Long,
        rating: Int = 3,
        type: String = "scheduled",
        device: String = B,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "op_id" to JsonString(id.replaceRange(9, 13, "00ff")),
            "op_type" to JsonString("review.logged"),
            "op_version" to JsonNumber(1.0),
            "entity_type" to JsonString("review_log"),
            "entity_id" to JsonString(id),
            "device_id" to JsonString(device),
            "hlc" to JsonString(hlc(hlcWall, device)),
            "payload" to JsonObject(
                linkedMapOf<String, JsonValue>(
                    "id" to JsonString(id),
                    "card_id" to JsonString(cardId),
                    "reviewed_at" to JsonNumber(reviewedAt.toDouble()),
                    "utc_offset_min" to JsonNull,
                    "review_type" to JsonString(type),
                    "rating" to JsonNumber(rating.toDouble()),
                ),
            ),
        ),
    )
}
