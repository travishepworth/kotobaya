package kotobaya.optimizer

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

/** A hand-off failure with one of the FSRS_PUBLISH.md 2.2 error codes; the child ends `failed` with `<code>: <message>`. */
class FsrsPublishFailure(val code: String, message: String) : RuntimeException(message) {
    val error: String get() = "$code: $message"

    companion object {
        const val UNSUPPORTED_FORMAT = "unsupported_format"
        const val INVALID_PAYLOAD = "invalid_payload"
        const val NOT_IMPROVED = "not_improved"
        const val WEIGHTS_OUT_OF_BOUNDS = "weights_out_of_bounds"
        const val UNKNOWN_PRESET = "unknown_preset"
        const val STALE_BASE = "stale_base"
        const val INTERNAL = "internal"
    }
}

/** The `fsrs-params-publish` job payload v1 (contracts/db/optimizer/fsrs-params-publish.v1.schema.json, closed). */
data class FsrsParamsPublishPayload(
    val parentJobId: UUID,
    val presetId: String,
    val baseWeightsVersion: Int,
    val fsrsVersion: String,
    val weights: List<Double>,
    val optimizedAt: Long,
    val reviewCount: Int,
    val holdoutReviewCount: Int,
    val logLossBefore: Double,
    val logLossAfter: Double,
    val rmseBins: Double,
) {
    companion object {
        private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val TOP = setOf(
            "format_version", "parent_job_id", "preset_id", "base_weights_version", "fsrs_version", "weights", "optimized_at",
            "review_count", "holdout_review_count", "log_loss_before", "log_loss_after", "rmse_bins",
        )

        /** The `parent_job_id` of a payload that may not validate, or null. */
        fun parentOf(p: JsonNode): UUID? =
            p.path("parent_job_id").takeIf { it.isTextual && UUID_RE.matches(it.asText()) }?.let { UUID.fromString(it.asText()) }

        /**
         * Validates [p] (FSRS_PUBLISH.md 2.2 step 1): `unsupported_format`, `invalid_payload`, `not_improved`.
         * The per-index weight bounds are checked on the op payload (step 2), not here.
         */
        fun parse(p: JsonNode): FsrsParamsPublishPayload {
            if (!p.isObject) bad("", "the payload is not an object")
            val fv = p.get("format_version")
            if (fv != null && !(fv.isIntegralNumber && fv.canConvertToLong() && fv.asLong() == 1L)) {
                throw FsrsPublishFailure(FsrsPublishFailure.UNSUPPORTED_FORMAT, "format_version $fv is not 1")
            }
            if (fv == null) bad("/format_version", "is required")
            for (name in p.fieldNames()) if (name !in TOP) bad("/$name", "unknown member")
            val fsrs = req(p, "fsrs_version")
            if (!fsrs.isTextual || fsrs.asText() != "6") bad("/fsrs_version", "must be \"6\"")
            val w = req(p, "weights")
            if (!w.isArray || w.size() != 21) bad("/weights", "must be an array of 21 numbers")
            val weights = w.mapIndexed { i, n ->
                if (!n.isNumber || !n.doubleValue().isFinite()) bad("/weights/$i", "must be a finite number")
                n.doubleValue()
            }
            val reviewCount = int(p, "review_count", 2, Int.MAX_VALUE.toLong()).toInt()
            val holdout = int(p, "holdout_review_count", 1, Int.MAX_VALUE - 1L).toInt()
            if (holdout >= reviewCount) bad("/holdout_review_count", "must be less than review_count")
            val before = num(p, "log_loss_before")
            val after = num(p, "log_loss_after")
            val rmse = num(p, "rmse_bins")
            if (before <= 0.0) bad("/log_loss_before", "must be > 0")
            if (after < 0.0) bad("/log_loss_after", "must be >= 0")
            if (rmse < 0.0) bad("/rmse_bins", "must be >= 0")
            if (!(after < before)) {
                throw FsrsPublishFailure(FsrsPublishFailure.NOT_IMPROVED, "log_loss_after $after is not below log_loss_before $before")
            }
            return FsrsParamsPublishPayload(
                parentJobId = UUID.fromString(uuid(p, "parent_job_id")),
                presetId = uuid(p, "preset_id"),
                baseWeightsVersion = int(p, "base_weights_version", 0, Int.MAX_VALUE - 1L).toInt(),
                fsrsVersion = "6",
                weights = weights,
                optimizedAt = int(p, "optimized_at", 0, 253_402_300_799_999L),
                reviewCount = reviewCount,
                holdoutReviewCount = holdout,
                logLossBefore = before,
                logLossAfter = after,
                rmseBins = rmse,
            )
        }

        private fun bad(at: String, msg: String): Nothing = throw FsrsPublishFailure(FsrsPublishFailure.INVALID_PAYLOAD, "$at: $msg".trim())

        private fun req(p: JsonNode, name: String): JsonNode = p.get(name)?.takeUnless { it.isNull } ?: bad("/$name", "is required")

        private fun uuid(p: JsonNode, name: String): String {
            val n = req(p, name)
            if (!n.isTextual || !UUID_RE.matches(n.asText())) bad("/$name", "must be a canonical lowercase UUID")
            return n.asText()
        }

        private fun int(p: JsonNode, name: String, min: Long, max: Long): Long {
            val n = req(p, name)
            if (!n.isIntegralNumber || !n.canConvertToLong() || n.asLong() < min || n.asLong() > max) bad("/$name", "must be an integer in $min..$max")
            return n.asLong()
        }

        private fun num(p: JsonNode, name: String): Double {
            val n = req(p, name)
            if (!n.isNumber || !n.doubleValue().isFinite()) bad("/$name", "must be a finite number")
            return n.doubleValue()
        }
    }
}
