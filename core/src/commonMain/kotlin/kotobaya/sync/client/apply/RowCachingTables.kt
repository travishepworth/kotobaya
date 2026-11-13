package kotobaya.sync.client.apply

import kotobaya.contracts.sync.JsonArray
import kotobaya.contracts.sync.JsonValue
import kotobaya.sync.DefaultCanonicalJson

/**
 * A read cache of [LearningTables.get] over [base], for one op or one batch of ops applied in one
 * transaction (BUG-8: the apply code and [AutoStatus] look the same rows up many times per op, and on a
 * device each lookup is a SQLite query).
 *
 * Transparent by construction: every write goes to [base] first and then **drops** the cached entry
 * (put or delete), so the next [get] of that key reads [base] again; [base]'s own
 * normalisation of a written row (defaults, column types) is therefore never second-guessed. [where]
 * and [all] always read [base], which is current because writes go through. Keys are compared in
 * their canonical JSON text, exactly like [InMemoryLearningTables]. Valid only while nothing else
 * writes [base] (the caller's transaction, applying ops through this view only).
 */
internal class RowCachingTables(val base: LearningTables) : LearningTables {
    private val cached = HashMap<LearningTable, HashMap<String, Row?>>()

    private fun keyText(key: List<JsonValue>): String = DefaultCanonicalJson.encode(JsonArray(key))

    private fun of(table: LearningTable): HashMap<String, Row?> = cached.getOrPut(table) { HashMap() }

    override fun get(table: LearningTable, key: List<JsonValue>): Row? {
        val rows = of(table)
        val k = keyText(key)
        if (rows.containsKey(k)) return rows[k]
        return base.get(table, key).also { rows[k] = it }
    }

    override fun put(table: LearningTable, row: Row) {
        base.put(table, row)
        of(table).remove(keyText(table.keyOf(row)))
    }

    override fun delete(table: LearningTable, key: List<JsonValue>) {
        base.delete(table, key)
        of(table).remove(keyText(key))
    }

    override fun where(table: LearningTable, column: String, value: JsonValue): List<Row> = base.where(table, column, value)

    override fun all(table: LearningTable): List<Row> = base.all(table)

    override fun clear(table: LearningTable) {
        base.clear(table)
        cached.remove(table)
    }

    /** Forgets everything (something wrote [base] behind this view, e.g. a startup replay). */
    fun invalidateAll() {
        cached.clear()
    }
}
