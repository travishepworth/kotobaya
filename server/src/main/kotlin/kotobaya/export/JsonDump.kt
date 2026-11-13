package kotobaya.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.server.Database
import java.io.OutputStream
import java.sql.Connection

/** One table of the dump: its key in `tables`, the row-to-JSON expression and the primary-key order. */
internal data class DumpTable(val name: String, val sql: String)

internal object DumpTables {
    private fun t(name: String, order: String, where: String = "", expr: String = "to_jsonb(t)") =
        DumpTable(name, "select ($expr)::text from app.$name t ${if (where.isEmpty()) "" else "where $where "}order by $order")

    /** Learning-data tables plus `media` and `parse_override`; `note.search_text` is generated and left out. */
    val core = listOf(
        t("note_type", "id"),
        t("note", "id", expr = "to_jsonb(t) - 'search_text'"),
        t("card", "id"),
        t("review_log", "id"),
        t("word_status", "entry_seq"),
        t("deck", "id"),
        t("deck_item", "deck_id, item_key"),
        t("confusable", "note_a_id, note_b_id, reason"),
        t("grammar_point", "id"),
        t("sentence_grammar_span", "sentence_id, grammar_point_id, start_offset, end_offset, origin", where = "origin = 'manual'"),
        t("settings", "key"),
        t("fsrs_params", "id"),
        t("media", "id"),
        t("parse_override", "id"),
    )

    /** Added by `include=media_content`; `sentence_grammar_span` is the same key, widened to the detected rows too. */
    val mediaContent = listOf(
        t("media_segment", "id"),
        t("sentence", "id"),
        t("occurrence", "segment_id, entry_seq"),
    )
    val allSpans = t("sentence_grammar_span", "sentence_id, grammar_point_id, start_offset, end_offset, origin")
}

/**
 * A single REPEATABLE READ snapshot, opened before the response starts (so failures still produce a problem) and
 * streamed table by table with a server-side cursor: rows are never collected in memory.
 */
internal class JsonDump(private val db: Database, private val serverVersion: String) {
    fun open(mediaContent: Boolean): Snapshot {
        val conn = db.dataSource.connection
        try {
            conn.autoCommit = false
            conn.createStatement().use {
                it.execute("set transaction isolation level repeatable read read only")
                it.execute("set local time zone 'UTC'")
            }
            val head = conn.createStatement().use { st ->
                st.executeQuery(
                    "select to_char(now() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"'), " +
                        "coalesce((select max(seq) from app.change), 0), " +
                        "(select version from ref.dict_release where is_current)",
                ).use { rs -> rs.next(); Triple(rs.getString(1), rs.getLong(2), rs.getString(3)) }
            }
            val tables = DumpTables.core.map { if (mediaContent && it.name == "sentence_grammar_span") DumpTables.allSpans else it } +
                (if (mediaContent) DumpTables.mediaContent else emptyList())
            return Snapshot(conn, serverVersion, head.first, head.second, head.third, tables)
        } catch (e: Throwable) {
            runCatching { conn.close() }
            throw e
        }
    }

    class Snapshot(
        private val conn: Connection,
        private val serverVersion: String,
        private val exportedAt: String,
        private val highWater: Long,
        private val dictVersion: String?,
        private val tables: List<DumpTable>,
    ) : AutoCloseable {
        suspend fun writeTo(out: OutputStream) = withContext(Dispatchers.IO) {
            val w = out.bufferedWriter(Charsets.UTF_8)
            w.write("{\"format_version\":1,\"exported_at\":${str(exportedAt)},\"server_version\":${str(serverVersion)}," +
                "\"high_water_seq\":$highWater,\"dict_release_version\":${dictVersion?.let { str(it) } ?: "null"},\"tables\":{")
            tables.forEachIndexed { i, table ->
                if (i > 0) w.write(",")
                w.write("${str(table.name)}:[")
                conn.prepareStatement(table.sql).use { st ->
                    st.fetchSize = 500
                    st.executeQuery().use { rs ->
                        var first = true
                        while (rs.next()) {
                            if (!first) w.write(",")
                            first = false
                            w.write(rs.getString(1))
                        }
                    }
                }
                w.write("]")
            }
            w.write("}}")
            w.flush()
        }

        override fun close() {
            runCatching { conn.rollback() }
            runCatching { conn.close() }
        }

        private fun str(s: String) = kotobaya.server.json.writeValueAsString(s)
    }
}
