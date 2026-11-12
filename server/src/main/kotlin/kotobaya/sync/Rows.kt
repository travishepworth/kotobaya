package kotobaya.sync

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotobaya.server.json
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.jooq.impl.DSL

/**
 * Row access for the op-apply path: a row is a Jackson object exactly as `to_jsonb(t)` renders it,
 * written back through `jsonb_populate_record(null::app.<table>, …)`, so one code path covers every
 * column type (uuid, text[], double precision[], timestamptz, jsonb). Table and column names are
 * always constants of this package, never payload text.
 *
 * Only the columns present in the object are written: an insert leaves the others at their column
 * default, an update touches only [update]'s `columns`.
 */
internal class Rows(private val dsl: DSLContext) {
    fun load(table: String, key: ObjectNode): ObjectNode? {
        val cols = key.fieldNames().asSequence().toList()
        val text = dsl.fetchOptional(
            "select to_jsonb(t)::text from app.$table t, jsonb_populate_record(null::app.$table, ?::jsonb) k where " +
                cols.joinToString(" and ") { "t.$it = k.$it" },
            json.writeValueAsString(key),
        ).map { it.get(0, String::class.java) }.orElse(null) ?: return null
        return json.readTree(text) as ObjectNode
    }

    fun insert(table: String, row: ObjectNode) {
        val cols = row.fieldNames().asSequence().toList()
        dsl.execute(
            "insert into app.$table (${cols.joinToString()}) select ${cols.joinToString { "k.$it" }} " +
                "from jsonb_populate_record(null::app.$table, ?::jsonb) k",
            json.writeValueAsString(row),
        )
    }

    /** Writes [columns] of [row] to the row whose [keyColumns] equal [row]'s. */
    fun update(table: String, keyColumns: List<String>, row: ObjectNode, columns: Collection<String>) {
        if (columns.isEmpty()) return
        val n = dsl.execute(
            "update app.$table as t set ${columns.joinToString { "$it = k.$it" }} " +
                "from jsonb_populate_record(null::app.$table, ?::jsonb) k where " +
                keyColumns.joinToString(" and ") { "t.$it = k.$it" },
            json.writeValueAsString(row),
        )
        check(n == 1) { "update of app.$table matched $n rows" }
    }

    fun exists(table: String, column: String, value: Any): Boolean =
        dsl.fetchExists(DSL.selectOne().from("app.$table").where("$column = ?", value))

    companion object {
        fun key(vararg pairs: Pair<String, Any?>): ObjectNode = SyncJson.obj().also { o ->
            for ((k, v) in pairs) o.set<JsonNode>(k, json.valueToTree(v))
        }
    }
}

/** Thrown when code tries to change an append-only table (SYNC.md 7.9, SCHEMA.md 4.2) outside the allowed undo write. */
class AppendOnlyViolation(message: String) : IllegalStateException(message)

/**
 * The application-layer twin of the `change_append_only` / `review_log_append_only` triggers: every
 * statement run through an op transaction's [DSLContext] is inspected, and an `UPDATE` or `DELETE`
 * of `app.change` or `app.review_log` is refused before it reaches Postgres. The only exception is
 * [UNDO_SQL], the one statement allowed to set or lower `review_log.undone_*` (SYNC.md 7.9 rule 3).
 */
internal object AppendOnlyGuard : ExecuteListener {
    /** `review.undone`: set or lower the undo; `undone_hlc` and `undone_at` in one statement. */
    const val UNDO_SQL =
        "update app.review_log set undone_hlc = ?, undone_at = ?::timestamptz where id = ? and (undone_hlc is null or undone_hlc > ?)"

    private val forbidden = Regex(
        "^\\s*(update\\s+(only\\s+)?|delete\\s+from\\s+(only\\s+)?|truncate\\s+(table\\s+)?)\"?app\"?\\.\"?(change|review_log)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun check(sql: String) {
        if (sql == UNDO_SQL) return
        if (forbidden.containsMatchIn(sql)) throw AppendOnlyViolation("app.change and app.review_log are append-only: $sql")
    }

    override fun executeStart(ctx: ExecuteContext) {
        ctx.sql()?.let(::check)
    }

    fun guard(dsl: DSLContext): DSLContext =
        DSL.using(dsl.configuration().derive(DefaultExecuteListenerProvider(this)))
}
