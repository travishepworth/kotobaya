package kotobaya.jobs

import com.fasterxml.jackson.databind.JsonNode
import net.logstash.logback.argument.StructuredArguments.entries
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

/**
 * OBS-1s: one log line per finished API job, built from the row the worker (or a server runner) wrote.
 *
 * - `failed` → ERROR with the job's `error`; `cancelled` → INFO.
 * - `succeeded` → INFO with the result's counts (top-level numbers; arrays as `<name>_count`, never their contents),
 *   WARN when rows were dropped (any `unmapped*` / `*skipped*` / `*dropped*` / `*unknown_grade*` count > 0),
 *   ERROR when output is zero from a non-empty input (kind rules in [zeroOutput]).
 */
object JobResultLog {
    private val log: Logger = LoggerFactory.getLogger("kotobaya.jobs.result")
    private val DROP = Regex("unmapped|skipped|dropped|unknown_grade|failed")

    data class Line(val level: Level, val message: String, val fields: Map<String, Any?>)

    fun log(job: JobRow, logger: Logger = log) {
        val line = assess(job)
        val args = arrayOf<Any?>(entries(line.fields))
        when (line.level) {
            Level.ERROR -> logger.error(line.message, *args)
            Level.WARN -> logger.warn(line.message, *args)
            else -> logger.info(line.message, *args)
        }
    }

    fun assess(job: JobRow): Line {
        val base = linkedMapOf<String, Any?>(
            "job_id" to job.id.toString(), "job_kind" to job.kind, "job_status" to job.status, "attempts" to job.attempts,
        )
        val duration = job.startedAt?.let { s -> job.finishedAt?.let { f -> f.toEpochMilli() - s.toEpochMilli() } }
        duration?.let { base["duration_ms"] = it }
        return when (job.status) {
            "failed" -> {
                base["error"] = job.error
                Line(Level.ERROR, "job ${job.kind} failed: ${job.error ?: "no error message"}", base)
            }
            "cancelled" -> Line(Level.INFO, "job ${job.kind} cancelled", base)
            else -> {
                val counts = counts(job.result)
                base.putAll(counts)
                val dropped = counts.filter { (k, v) -> DROP.containsMatchIn(k) && v > 0 }
                val zero = zeroOutput(job.kind, job.result)
                when {
                    zero != null -> Line(Level.ERROR, "job ${job.kind} produced nothing: $zero", base)
                    dropped.isNotEmpty() ->
                        Line(Level.WARN, "job ${job.kind} dropped rows: " + dropped.entries.joinToString(", ") { "${it.key}=${it.value}" }, base)
                    else -> Line(Level.INFO, "job ${job.kind} succeeded", base)
                }
            }
        }
    }

    /** Top-level counts of a result: numbers as-is, arrays as `<name>_count`, nested objects one level (`a.b`). */
    internal fun counts(result: JsonNode?): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        fun add(prefix: String, node: JsonNode) {
            node.fields().forEach { (k, v) ->
                val key = prefix + k
                when {
                    v.isIntegralNumber -> out[key] = v.asLong()
                    v.isArray -> out[key + "_count"] = v.size().toLong()
                    v.isObject && prefix.isEmpty() -> add("$key.", v)
                }
            }
        }
        if (result != null && result.isObject) add("", result)
        return out
    }

    /**
     * Why a succeeded job's output is zero from a non-empty input, or null when it is not.
     *
     * jpdb-history-import (the 0.1.9 incident: a non-empty export imported 0 cards): input rows
     * (`vocabulary_total + kanji_total`) > 0, yet no card was created, no note already existed (a re-import) and no
     * word status was set (a status-only export).
     */
    internal fun zeroOutput(kind: String, result: JsonNode?): String? {
        if (result == null || !result.isObject) return null
        fun n(k: String) = result.path(k).asLong(0)
        return when (kind) {
            "jpdb-history-import" -> {
                val input = n("vocabulary_total") + n("kanji_total")
                val output = n("cards_created") + n("kanji_cards_created") + n("notes_existing") + n("kanji_notes_existing") +
                    n("word_statuses_set")
                if (input > 0 && output == 0L) "$input input rows, 0 cards created, 0 existing notes, 0 word statuses" else null
            }
            else -> null
        }
    }
}
