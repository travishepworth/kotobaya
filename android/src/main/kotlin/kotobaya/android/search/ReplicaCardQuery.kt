package kotobaya.android.search

import app.cash.sqldelight.db.SqlDriver
import java.text.Normalizer
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.queue.QueueSettings
import kotobaya.android.queue.rolloverMs
import kotobaya.android.queue.studyDayOf
import kotobaya.android.shell.api.actions.CardQuery
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.android.shell.api.mining.SearchMatch
import kotobaya.db.app.AppDatabase
import kotobaya.search.DefaultSearchQueryParser
import kotobaya.search.sql.Dialect
import kotobaya.search.sql.DictFormRow
import kotobaya.search.sql.ResidualRow
import kotobaya.search.sql.SearchContext
import kotobaya.search.sql.SearchSqlCompiler
import kotobaya.search.sql.SmartDeckRef
import kotobaya.search.sql.SqlFragment
import org.json.JSONArray

/**
 * The card search language over the replica, every term evaluated on the device (SHELL.md 4.3 `listCards` … with
 * `query`, C-31): it never answers [CardQueryResult.Forward]. Parsing and compiling are core's
 * ([DefaultSearchQueryParser], [SearchSqlCompiler], SQLite dialect, residual check); the context is the server's
 * (`CardSearch.compile`): tz-aware study days from the replica's settings, the replica's live smart decks, no hot set.
 *
 * Dictionary forms (C-24) come from the L-7 lookup, steps 1-2, through two indexed lookups instead of a size guard:
 * - the compiler's `dict_entry_form` statement is run on the installed package restricted to the collection's own
 *   entry seqs (`seq IN (...)`, the table's primary key), so its cost follows the collection, not the number of
 *   dictionary entries a pattern matches (a one-kana term matching 100 000 entries costs what an exact word costs);
 * - entries the package lacks come from the sidecar cache ([InstalledDictionary.entry]), memoised per
 *   [InstalledDictionary.generation]. A note whose entry is in neither does not match through the dictionary
 *   (documented difference until gap fill caches it); with no package and an empty cache text terms match note text.
 *
 * The compiler binds the matched seqs as one `,s1,s2,` string tested with `instr` per note; that statement is
 * rewritten ([indexSeqLists]) to an uncorrelated `IN (subquery)` SQLite evaluates once into an ephemeral index.
 *
 * Blocking; call off the main thread. Thread-safe.
 */
class ReplicaCardQuery(
    private val driver: SqlDriver,
    private val db: AppDatabase,
    private val dictionary: InstalledDictionary?,
) : CardQuery {
    override fun select(query: String, nowMs: Long): CardQueryResult {
        val parsed = DefaultSearchQueryParser.parse(query)
        val node = parsed.query
        if (node == null || parsed.errors.isNotEmpty()) {
            return CardQueryResult.Invalid(parsed.errors.map { it.code.name.lowercase() to it.span.start })
        }
        val st = QueueSettings.from(db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ })
        val today = studyDayOf(nowMs, st.timezone, st.dayRolloverHour)
        val smart = driver.rows("SELECT name, query FROM deck WHERE type = 'smart' AND deleted_at IS NULL AND query IS NOT NULL") {
            it.getString(0).orEmpty() to it.getString(1).orEmpty()
        }.mapNotNull { (name, q) ->
            val p = DefaultSearchQueryParser.parse(q)
            p.query?.takeIf { p.errors.isEmpty() }?.let { SmartDeckRef(name, it) }
        }
        val ctx = SearchContext(
            studyDay = today.day,
            nowMs = nowMs,
            leechThreshold = st.leechThreshold,
            smartDecks = smart,
            dayStart = { off -> rolloverMs(st.timezone, st.dayRolloverHour, today.date.plusDays(off.toLong())) },
            dictForms = dictForms(),
        )
        val compiled = SearchSqlCompiler.compile(node, Dialect.SQLITE, ctx)
        val f = indexSeqLists(compiled.select())
        val ids = driver.rows(f.sql, f.params) { c ->
            val id = checkNotNull(c.getString(0))
            if (!compiled.residual) {
                id
            } else {
                val probes = compiled.probes.indices.map { (c.getLong(7 + it) ?: 0L) != 0L }
                val row = ResidualRow(
                    c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
                    c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getString(6).orEmpty(), probes,
                )
                id.takeIf { compiled.accepts(row) }
            }
        }
        return CardQueryResult.Ids(ids.filterNotNull().distinct().sorted())
    }

    /**
     * C-24 over L-7 steps 1-2, restricted to the collection's entries (no rows: the branch cannot match, so text terms
     * match note text only). Only a query with a text term pays for the lookups (the compiler calls this per pattern).
     */
    private fun dictForms(): ((SqlFragment) -> List<DictFormRow>)? {
        val dict = dictionary ?: return null
        val packaged = dict.isAvailable()
        val collection by lazy {
            driver.rows("SELECT DISTINCT entry_seq FROM note WHERE entry_seq IS NOT NULL AND deleted_at IS NULL") { it.getLong(0) ?: 0L }
        }
        val sidecar by lazy { sidecarForms(dict, collection, packaged) }
        return { f ->
            val out = ArrayList<DictFormRow>()
            if (packaged) {
                for (chunk in collection.chunked(SEQ_CHUNK)) {
                    // the compiler's statement, narrowed to the collection through dict_entry_form's primary key (seq, ...)
                    val sql = "SELECT seq, form, norm FROM (${f.sql}) WHERE seq IN (${chunk.joinToString(",")})"
                    dict.formRows(sql, f.params.map { it.toString() }) { true }?.let(out::addAll)
                }
            }
            // a superset is fine: the compiler keeps only the rows whose form or norm contains the pattern
            sidecar.forEach(out::addAll)
            out
        }
    }

    private var memo: Pair<Long, HashMap<Long, List<DictFormRow>>>? = null

    /** Form rows of the collection's entries the package lacks, from the sidecar cache (memoised per generation). */
    @Synchronized
    private fun sidecarForms(dict: InstalledDictionary, collection: List<Long>, packaged: Boolean): List<List<DictFormRow>> {
        val gen = dict.generation
        val known = memo?.takeIf { it.first == gen }?.second ?: HashMap<Long, List<DictFormRow>>().also { memo = gen to it }
        val unknown = collection.filter { it !in known }
        if (unknown.isNotEmpty()) {
            val inPackage = if (packaged) dict.presentEntries(unknown) else emptySet()
            for (seq in unknown) {
                known[seq] = if (seq in inPackage) emptyList() else sidecarRows(dict, seq)
            }
        }
        return collection.mapNotNull { known[it]?.takeIf(List<DictFormRow>::isNotEmpty) }
    }

    private fun sidecarRows(dict: InstalledDictionary, seq: Long): List<DictFormRow> {
        val e = runCatching { dict.entry(seq) }.getOrNull()?.takeIf { !it.retired } ?: return emptyList()
        // dict_entry_form rows of the package: kanji forms then readings; norm as SCHEMA.md 4.5 (NFKC, katakana folded)
        return (texts(e.kanjiForms) + texts(e.readings)).map { form ->
            DictFormRow(seq, form, SearchMatch.foldKatakana(Normalizer.normalize(form, Normalizer.Form.NFKC)))
        }
    }

    private fun texts(a: JSONArray): List<String> = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("text")?.takeIf(String::isNotEmpty) }

    internal companion object {
        /** Seqs per `IN (...)` literal list on the package (integers from the replica, never user text). */
        const val SEQ_CHUNK = 5000

        /** Exactly what core's SQLite compiler emits for a C-24 seq list (`SearchSqlCompiler.dictForms`). */
        const val SEQ_LIST_TEST = "instr(?, ',' || n.entry_seq || ',') > 0"

        private const val WIDTH = 19

        /**
         * Rewrites each [SEQ_LIST_TEST] (bound to `,s1,s2,...,`) to `n.entry_seq IN (<seqs>)`, the seqs decoded from a
         * fixed-width string by a recursive CTE: an uncorrelated subquery, so SQLite builds its ephemeral index once
         * instead of scanning the whole list for every note. Placeholders are renumbered; nothing else changes.
         */
        fun indexSeqLists(f: SqlFragment): SqlFragment {
            if (!f.sql.contains(SEQ_LIST_TEST)) return f
            val sql = StringBuilder(f.sql.length + 256)
            val params = ArrayList<Any>(f.params.size + 4)
            var p = 0
            var i = 0
            val s = f.sql
            while (i < s.length) {
                val ch = s[i]
                when {
                    s.startsWith(SEQ_LIST_TEST, i) -> {
                        val seqs = (f.params[p++] as String).split(',').filter(String::isNotEmpty)
                        sql.append(
                            "n.entry_seq IN (WITH RECURSIVE kb_seq(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM kb_seq WHERE i + 1 < ?) " +
                                "SELECT CAST(substr(?, i * $WIDTH + 1, $WIDTH) AS INTEGER) FROM kb_seq)",
                        )
                        params += seqs.size.toLong()
                        params += seqs.joinToString("") { it.padStart(WIDTH, '0') }
                        i += SEQ_LIST_TEST.length
                    }
                    ch == '\'' -> {
                        val end = s.indexOf('\'', i + 1).let { if (it < 0) s.length - 1 else it }
                        sql.append(s, i, end + 1)
                        i = end + 1
                    }
                    ch == '?' -> {
                        sql.append('?')
                        params += f.params[p++]
                        i++
                    }
                    else -> {
                        sql.append(ch)
                        i++
                    }
                }
            }
            check(p == f.params.size) { "placeholder count changed" }
            return SqlFragment(sql.toString(), params)
        }
    }
}
