package kotobaya.android.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/** Runs a read-only statement on the replica with String/Long/Double binds (the types core's compiler emits). */
internal fun <T> SqlDriver.rows(sql: String, params: List<Any> = emptyList(), map: (SqlCursor) -> T): List<T> =
    executeQuery(
        null,
        sql,
        { c ->
            val out = ArrayList<T>()
            while (c.next().value) out += map(c)
            QueryResult.Value(out)
        },
        params.size,
        {
            params.forEachIndexed { i, p ->
                when (p) {
                    is String -> bindString(i, p)
                    is Long -> bindLong(i, p)
                    is Int -> bindLong(i, p.toLong())
                    is Double -> bindDouble(i, p)
                    else -> error("unsupported bind ${p::class}")
                }
            }
        },
    ).value

internal fun placeholders(n: Int): String = List(n) { "?" }.joinToString(",")

/** Effective word status per entry (no row = `new`), from the replica's `word_status`. */
internal fun SqlDriver.wordStatuses(seqs: Collection<Long>): Map<Long, String> {
    if (seqs.isEmpty()) return emptyMap()
    val out = HashMap<Long, String>()
    for (chunk in seqs.chunked(400)) {
        rows("SELECT entry_seq, status FROM word_status WHERE entry_seq IN (${placeholders(chunk.size)})", chunk) {
            out[it.getLong(0)!!] = it.getString(1)!!
        }
    }
    return out
}
