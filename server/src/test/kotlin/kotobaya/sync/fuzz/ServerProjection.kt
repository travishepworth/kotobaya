package kotobaya.sync.fuzz

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.fsrs.CardSchedule
import kotobaya.contracts.fsrs.CardState
import kotobaya.server.Database
import kotobaya.server.json
import kotobaya.sync.SyncJson
import java.util.TreeMap

/**
 * The server's tables in the device representation (`contracts/db/SCHEMA.md` 5.1) and the SYNC.md 11
 * projection, comparable with [RefReplica.projection]: instants as epoch ms, arrays as lists,
 * merge losers only on `id`, `merged_into_id`, `deleted_at`, the cache of live non-loser cards.
 */
object ServerProjection {
    private val instants = setOf("created_at", "updated_at", "deleted_at", "buried_until", "reviewed_at", "manual_due_at", "undone_at")

    fun of(db: Database): Map<String, Map<String, Any?>> = db.txBlocking { dsl ->
        val out = TreeMap<String, Map<String, Any?>>()
        for (table in listOf("note", "card", "review_log")) {
            val rows = TreeMap<String, Any?>()
            for (rec in dsl.fetch("select to_jsonb(t)::text from app.$table t")) {
                val n = json.readTree(rec.get(0, String::class.java))
                val r = TreeMap<String, Any?>()
                if (!n["merged_into_id"].let { it == null || it.isNull }) {
                    r["id"] = n["id"].asText(); r["merged_into_id"] = n["merged_into_id"].asText(); r["deleted_at"] = SyncJson.ms(n["deleted_at"])
                } else {
                    for (c in RefReplica.COLUMNS.getValue(table)) r[c] = if (c in instants) SyncJson.ms(n[c]) else RefReplica.canon(plain(n[c]))
                    if (table == "card" && n["deleted_at"].isNull) r["cache"] = cache(n)
                }
                rows[n["id"].asText()] = r
            }
            out[table] = rows
        }
        out
    }

    private fun cache(n: JsonNode): Map<String, Any?> {
        fun d(c: String) = n[c]?.takeUnless { it.isNull }?.asDouble()
        fun i(c: String) = n[c]?.takeUnless { it.isNull }?.asInt()
        val s = CardSchedule(
            state = CardState.entries.first { it.wire == n["state"].asText() }, step = i("step"), stability = d("stability"),
            difficulty = d("difficulty"), dueAt = SyncJson.ms(n["due_at"]), intervalDays = i("interval_days"),
            lastReviewAt = SyncJson.ms(n["last_review_at"]), reps = n["reps"].asInt(), lapses = n["lapses"].asInt(),
        )
        return RefReplica.cacheMap(s, n["last_review_id"]?.takeUnless { it.isNull }?.asText(), n["weights_version"].asInt())
    }

    /** The first few differences between two projections, for assertion messages. */
    fun diff(a: Map<String, Map<String, Any?>>, b: Map<String, Map<String, Any?>>, max: Int = 5): List<String> {
        val out = mutableListOf<String>()
        for (table in (a.keys + b.keys).toSortedSet()) {
            val x = a[table].orEmpty()
            val y = b[table].orEmpty()
            for (k in (x.keys + y.keys).toSortedSet()) {
                if (x[k] != y[k]) out += "$table $k:\n   ${x[k]}\n   ${y[k]}"
                if (out.size >= max) return out
            }
        }
        return out
    }
}
