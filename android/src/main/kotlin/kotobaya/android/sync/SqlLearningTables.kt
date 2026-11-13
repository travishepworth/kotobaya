package kotobaya.android.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.apply.Row

/** How a column of `Learning.sq` / `Media.sq` maps to the device row representation (SCHEMA.md 5.1). */
internal enum class ColumnKind { TEXT, INTEGER, REAL, BOOLEAN, JSON }

internal class Column(val name: String, val kind: ColumnKind, val notNull: Boolean)

/**
 * Column layout of every [LearningTable], read from the live schema (`PRAGMA table_info`) so it never
 * drifts from the contract `.sq` files. SQLite cannot tell booleans and JSON text apart from plain
 * integers and text, so those come from the SCHEMA.md 5.1 mapping below (Postgres `boolean`, `jsonb`
 * and array columns).
 */
internal class LearningSchema private constructor(private val columns: Map<LearningTable, List<Column>>) {
    fun columns(table: LearningTable): List<Column> = columns.getValue(table)

    fun column(table: LearningTable, name: String): Column =
        columns(table).firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("${table.sqlName} has no column $name")

    companion object {
        /** Postgres `boolean` columns (SQLite `INTEGER AS kotlin.Boolean`). */
        private val BOOLEAN: Map<String, Set<String>> = mapOf(
            "grammar_point" to setOf("is_stub"),
            "note_type" to setOf("builtin"),
            "fsrs_params" to setOf("fuzz"),
            "card" to setOf("suspended"),
            "deck" to setOf("enabled"),
        )

        /** Postgres `jsonb` and array columns (SQLite TEXT holding JSON). */
        private val JSON: Map<String, Set<String>> = mapOf(
            "grammar_point" to setOf("detection_rules", "related_ids", "links", "clocks"),
            "note_type" to setOf("fields", "templates", "clocks"),
            "note" to setOf("fields", "tags", "clocks"),
            "fsrs_params" to setOf("learning_steps_s", "relearning_steps_s", "easy_days", "weights", "optimizer_stats", "clocks"),
            "card" to setOf("clocks"),
            "deck" to setOf("settings", "clocks"),
            "settings" to setOf("value"),
            "media" to setOf("clocks"),
            "sentence" to setOf("tokens", "location"),
            "occurrence" to setOf("sentence_ids"),
        )

        fun read(driver: SqlDriver): LearningSchema = LearningSchema(
            LearningTable.entries.associateWith { table ->
                val cols = driver.executeQuery(null, "PRAGMA table_info(${table.sqlName})", { c ->
                    val out = ArrayList<Triple<String, String, Boolean>>()
                    while (c.next().value) out += Triple(c.str(1), (c.getString(2) ?: "").uppercase(), c.getLong(3) == 1L)
                    QueryResult.Value(out)
                }, 0).value
                check(cols.isNotEmpty()) { "table ${table.sqlName} does not exist" }
                cols.map { (name, declared, notNull) ->
                    val kind = when {
                        name in BOOLEAN[table.sqlName].orEmpty() -> ColumnKind.BOOLEAN
                        name in JSON[table.sqlName].orEmpty() -> ColumnKind.JSON
                        declared.startsWith("INTEGER") -> ColumnKind.INTEGER
                        declared.startsWith("REAL") -> ColumnKind.REAL
                        else -> ColumnKind.TEXT
                    }
                    Column(name, kind, notNull)
                }
            },
        )
    }
}

/**
 * [LearningTables] over the app database (T303b's apply code writes through it). Every call runs on
 * the caller's SQLite transaction (the [SqlDelightDeviceStore] one); nothing here opens its own.
 *
 * Rows are converted per SCHEMA.md 5.1: TEXT ⇄ [JsonString], INTEGER/REAL ⇄ [JsonNumber], boolean
 * INTEGER 0/1 ⇄ [JsonBool], JSON TEXT ⇄ the decoded value (written with `CanonicalJson`), NULL ⇄
 * [JsonNull]. [put] updates the row in place (keeping its rowid, so [all] and [where] return rows in
 * insertion order exactly like the in-memory tables) or inserts it; it never uses `INSERT OR REPLACE`,
 * which could silently delete a different row on a secondary UNIQUE constraint.
 *
 * Anything the schema cannot hold (an unknown column, a fractional number in an INTEGER column, a
 * non-boolean in a boolean column) throws [IllegalStateException], so the transaction rolls back
 * rather than storing something other than what the apply code wrote.
 */
class SqlLearningTables internal constructor(
    private val driver: SqlDriver,
    private val schema: LearningSchema,
    /** True inside [SqlDelightDeviceStore.read]: every write throws (it would autocommit on its own). */
    private val readOnly: Boolean = false,
) : LearningTables {
    override fun get(table: LearningTable, key: List<JsonValue>): Row? {
        val (where, keyCols) = keyClause(table, key)
        if (key.any { it == JsonNull }) return null
        return select(table, "WHERE $where", keyCols.size) { stmt -> bindAll(stmt, 0, keyCols, key) }.firstOrNull()
    }

    override fun put(table: LearningTable, row: Row) {
        if (readOnly) readOnlyWrite()
        val cols = row.keys.map { schema.column(table, it) }
        val key = table.keyOf(row)
        check(key.none { it == JsonNull }) { "${table.sqlName}: key column is NULL in $row" }
        val values = cols.map { row.getValue(it.name) }
        val (where, keyCols) = keyClause(table, key)
        val setCols = cols.filter { it.name !in table.keyColumns }
        val updated = if (setCols.isEmpty()) {
            if (get(table, key) != null) 1L else 0L
        } else {
            val setValues = setCols.map { row.getValue(it.name) }
            driver.execute(
                null,
                "UPDATE ${table.sqlName} SET ${setCols.joinToString { "${it.name} = ?" }} WHERE $where",
                setCols.size + keyCols.size,
            ) {
                bindAll(this, 0, setCols, setValues)
                bindAll(this, setCols.size, keyCols, key)
            }.value
        }
        if (updated > 0L) return
        driver.execute(
            null,
            "INSERT INTO ${table.sqlName} (${cols.joinToString { it.name }}) VALUES (${cols.joinToString { "?" }})",
            cols.size,
        ) { bindAll(this, 0, cols, values) }
    }

    override fun delete(table: LearningTable, key: List<JsonValue>) {
        if (readOnly) readOnlyWrite()
        if (key.any { it == JsonNull }) return
        val (where, keyCols) = keyClause(table, key)
        driver.execute(null, "DELETE FROM ${table.sqlName} WHERE $where", keyCols.size) { bindAll(this, 0, keyCols, key) }
    }

    override fun where(table: LearningTable, column: String, value: JsonValue): List<Row> {
        val col = schema.column(table, column)
        // `IS` matches NULL for NULL and equality otherwise, exactly the in-memory JSON equality
        return select(table, "WHERE ${col.name} IS ?", 1) { stmt -> bind(stmt, 0, col, value) }
    }

    override fun all(table: LearningTable): List<Row> = select(table, "", 0) {}

    override fun clear(table: LearningTable) {
        if (readOnly) readOnlyWrite()
        driver.execute(null, "DELETE FROM ${table.sqlName}", 0)
    }

    /** Cards whose cache is the refused cache (SYNC.md 9.4): the durable diagnostic count. */
    fun refusedCacheCount(): Int = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM card WHERE last_review_id = ?",
        { c -> c.next(); QueryResult.Value(c.getLong(0)?.toInt() ?: 0) },
        1,
    ) { bindString(0, Reserved.REPLAY_REFUSED_ID) }.value

    // ---------------------------------------------------------------------------------------------

    private fun keyClause(table: LearningTable, key: List<JsonValue>): Pair<String, List<Column>> {
        require(key.size == table.keyColumns.size) { "${table.sqlName}: key $key does not match ${table.keyColumns}" }
        val cols = table.keyColumns.map { schema.column(table, it) }
        return cols.joinToString(" AND ") { "${it.name} = ?" } to cols
    }

    private fun select(table: LearningTable, tail: String, params: Int, binders: (SqlPreparedStatement) -> Unit): List<Row> {
        val cols = schema.columns(table)
        return driver.executeQuery(
            null,
            "SELECT ${cols.joinToString { it.name }} FROM ${table.sqlName} $tail ORDER BY rowid",
            { c ->
                val out = ArrayList<Row>()
                while (c.next().value) out += readRow(table, cols, c)
                QueryResult.Value(out)
            },
            params,
        ) { binders(this) }.value
    }

    private fun readRow(table: LearningTable, cols: List<Column>, c: SqlCursor): Row {
        val row = LinkedHashMap<String, JsonValue>(cols.size)
        cols.forEachIndexed { i, col ->
            row[col.name] = when (col.kind) {
                ColumnKind.TEXT -> c.getString(i)?.let(::JsonString)
                ColumnKind.INTEGER -> c.getLong(i)?.let { JsonNumber(it.toDouble()) }
                ColumnKind.REAL -> c.getDouble(i)?.let(::JsonNumber)
                ColumnKind.BOOLEAN -> c.getLong(i)?.let { JsonBool(it != 0L) }
                ColumnKind.JSON -> c.getString(i)?.let { text ->
                    DefaultCanonicalJson.decode(text).value
                        ?: throw IllegalStateException("${table.sqlName}.${col.name} holds invalid JSON")
                }
            } ?: JsonNull
        }
        return row
    }

    private fun bindAll(stmt: SqlPreparedStatement, offset: Int, cols: List<Column>, values: List<JsonValue>) {
        cols.forEachIndexed { i, col -> bind(stmt, offset + i, col, values[i]) }
    }

    private fun bind(stmt: SqlPreparedStatement, index: Int, col: Column, v: JsonValue) {
        if (v == JsonNull && col.kind != ColumnKind.JSON) {
            stmt.bindString(index, null)
            return
        }
        when (col.kind) {
            ColumnKind.TEXT -> stmt.bindString(index, (v as? JsonString)?.value ?: fail(col, v))
            ColumnKind.INTEGER -> {
                val d = (v as? JsonNumber)?.value ?: fail(col, v)
                val l = d.toLong()
                if (l.toDouble() != d) fail(col, v)
                stmt.bindLong(index, l)
            }
            ColumnKind.REAL -> stmt.bindDouble(index, (v as? JsonNumber)?.value ?: fail(col, v))
            ColumnKind.BOOLEAN -> stmt.bindLong(index, if ((v as? JsonBool)?.value ?: fail(col, v)) 1L else 0L)
            // JSON null is SQL NULL in a nullable column and the text `null` in a NOT NULL one (Postgres
            // jsonb 'null', e.g. a setting set to null); both read back as JsonNull
            ColumnKind.JSON -> stmt.bindString(index, if (v == JsonNull && !col.notNull) null else DefaultCanonicalJson.encode(v))
        }
    }

    private fun fail(col: Column, v: JsonValue): Nothing =
        throw IllegalStateException("column ${col.name} (${col.kind}) cannot hold $v")
}
