package kotobaya.android.shell.api.stats

import kotobaya.android.shell.api.LocalRoutes
import kotobaya.android.shell.api.ShellResponse
import kotobaya.android.shell.api.review.AnswerTestSupport
import kotobaya.android.shell.parity.ParityDevice
import kotobaya.android.shell.parity.ParityJson
import kotobaya.android.shell.parity.ParityScenario
import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue

/** Helpers of the LF-A3 tests: [ParityDevice]s over every registered route. */
internal object StatsTestSupport {
    const val A = "01931f3e-0000-7000-8000-0000000000a1"
    const val C1 = "0193a1b2-0002-7000-8000-000000000010"
    const val C2 = "0193a1b2-0002-7000-8000-000000000020"
    const val C3 = "0193a1b2-0002-7000-8000-000000000030"

    /** 2026-11-20T10:00:00Z, the `clock_ms` of the stats scenarios. */
    const val T0 = 1_795_168_800_000L
    const val MIN = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR

    /** A device seeded with the seed log of parity scenario [scenario]. */
    fun device(scenario: String): ParityDevice {
        Class.forName("org.sqlite.JDBC")
        val s = ParityScenario.load(scenario)
        return ParityDevice(s.deviceId, s.clockMs, LocalRoutes.all).also { it.pull(s.seedLog, "seed") }
    }

    fun ParityDevice.get(path: String, query: String = "", at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request("GET", path, query, null)
    }

    fun ParityDevice.send(method: String, path: String, body: String, at: Long? = null): ShellResponse {
        at?.let { clock.now = it }
        return request(method, path, "", ParityJson.parse(body))
    }

    fun ShellResponse.json(): JsonObject = ParityJson.obj(ParityJson.parse(body ?: error("no body (status $status)")))

    fun ShellResponse.at(pointer: String): JsonValue? = ParityJson.at(json(), pointer)

    /** `status pointer code` of the first error of a problem, else `status code`. */
    fun ShellResponse.problem(): String = with(AnswerTestSupport) { problem() }

    fun ShellResponse.days(): List<JsonObject> = (json().members["days"] as JsonArray).items.map(ParityJson::obj)

    fun JsonValue?.num(): Long = ParityJson.long(this)

    fun reviewId(n: Int): String = "0193a1b2-0003-7000-8000-%012x".format(0x500 + n)

    /** A `review.logged` of device A in pushed form. [offsetMin] null = unknown offset (the zone setting decides). */
    fun review(
        n: Int,
        cardId: String,
        reviewedAt: Long,
        offsetMin: Int?,
        type: String = "scheduled",
        rating: Int = 3,
        durationMs: Long = 1000,
    ): JsonObject = op(n, "review.logged", "review_log", reviewId(n)) {
        linkedMapOf(
            "id" to JsonString(reviewId(n)),
            "card_id" to JsonString(cardId),
            "reviewed_at" to JsonNumber(reviewedAt.toDouble()),
            "utc_offset_min" to (offsetMin?.let { JsonNumber(it.toDouble()) } ?: JsonNull),
            "review_type" to JsonString(type),
            "rating" to JsonNumber(rating.toDouble()),
            "duration_ms" to JsonNumber(durationMs.toDouble()),
        )
    }

    /** `review.undone` of the review [reviewN] of [review]; [n] names the op. */
    fun undo(n: Int, reviewN: Int): JsonObject =
        op(n, "review.undone", "review_log", reviewId(reviewN)) { linkedMapOf("review_id" to JsonString(reviewId(reviewN))) }

    fun setting(n: Int, key: String, value: JsonValue): JsonObject =
        op(n, "settings.set", "settings", key) { linkedMapOf("key" to JsonString(key), "value" to value) }

    private fun op(n: Int, type: String, entity: String, entityId: String, payload: () -> LinkedHashMap<String, JsonValue>): JsonObject =
        JsonObject(
            linkedMapOf(
                "op_id" to JsonString("0193a1b2-00ff-7003-8000-%012x".format(n)),
                "op_type" to JsonString(type),
                "op_version" to JsonNumber(1.0),
                "entity_type" to JsonString(entity),
                "entity_id" to JsonString(entityId),
                "device_id" to JsonString(A),
                // ordered by n and after every seed op; `reviewed_at` is independent of the HLC
                "hlc" to JsonString(AnswerTestSupport.hlc(T0 + n * 1000L, A)),
                "payload" to JsonObject(payload()),
            ),
        )
}
