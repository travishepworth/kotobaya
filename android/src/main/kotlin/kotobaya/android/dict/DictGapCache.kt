package kotobaya.android.dict

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.sync.SqlDelightDeviceStore

/**
 * Step 1 of SHELL.md rule L-7: the installed package, nothing else. [InstalledDictionary] is the production source;
 * the parity harness supplies one over JDBC.
 */
interface PackageSource {
    /** The installed release, or null when no package is installed. */
    fun info(): PackageInfo?

    fun packageEntry(seq: Long): DictEntryDetail?

    fun packageKanji(literal: String): KanjiDetail?

    /** The subset of [seqs] the installed package holds (empty when none is installed). */
    fun presentEntries(seqs: Collection<Long>): Set<Long>

    /** The subset of [literals] the installed package holds. */
    fun presentKanji(literals: Collection<String>): Set<String>
}

/** What a sidecar row is keyed by: an entry `seq` or a kanji literal. */
enum class GapKind(val tag: String) { ENTRY("entry"), KANJI("kanji") }

data class GapRef(val kind: GapKind, val key: String) {
    companion object {
        fun entry(seq: Long): GapRef = GapRef(GapKind.ENTRY, seq.toString())

        fun kanji(literal: String): GapRef = GapRef(GapKind.KANJI, literal)
    }
}

/**
 * One sidecar row. [body] is the server's `DictEntry` / `Kanji` JSON without the per-user overlay, or null for a
 * negative result (the server answered `404` under release [releaseId]; L-7 "Negative results"). [releaseId] is the
 * last known server release when the answer was fetched (0 = none known).
 */
data class GapRow(val ref: GapRef, val releaseId: Long, val body: String?, val fetchedMs: Long)

/** Counts for the device-settings dictionary row and `ShellDictInstall.gap_fill` (as of the last gap-fill run). */
data class GapFillCounts(val cached: Int, val missing: Int)

/**
 * The sidecar cache of SHELL.md L-7 (SHELL-L9): dictionary entries and kanji fetched from the server one by one for
 * references the installed package lacks. Lives in the learning database (never in the read-only package file), in a
 * device-only table created at run time (`IF NOT EXISTS`, like the note indexes: no schema migration), never synced.
 * Also keeps the device state of L-7 in `sync_state`: the last known server dictionary release.
 *
 * Runs on any [SqlDriver] (Android, or JDBC in tests).
 */
class DictGapCache(private val store: SqlDelightDeviceStore, private val driver: SqlDriver) {
    @Volatile private var installed = false

    /** Bumped by every write; in-memory indexes built over the rows ([snapshot]) are valid for one generation. */
    @Volatile var generation: Long = 0
        private set

    /** Full scans of a kind ([rows]); lets a test assert that searching does not rescan the table. */
    @Volatile internal var scans: Int = 0
        private set

    private var memo: Triple<Any, Long, Any>? = null

    /**
     * The in-memory index [build] derives from the rows, rebuilt only when [key] or the cache [generation] changed
     * (search-as-you-type must not decode every cached entry per keystroke).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> snapshot(key: Any, build: () -> T): T = synchronized(this) {
        val gen = generation
        memo?.let { if (it.first == key && it.second == gen) return it.third as T }
        build().also { memo = Triple(key, gen, it) }
    }

    /** Creates the table if missing. Idempotent. */
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            store.transaction { driver.execute(null, DDL, 0) }
            installed = true
        }
    }

    fun row(ref: GapRef): GapRow? {
        install()
        return store.read {
            driver.executeQuery(
                null, "SELECT kind, key, release_id, body, fetched_ms FROM $TABLE WHERE kind = ? AND key = ?",
                { c -> QueryResult.Value(if (c.next().value) read(c) else null) }, 2,
            ) { bindString(0, ref.kind.tag); bindString(1, ref.key) }.value
        }
    }

    /** Every row of [kind]. */
    fun rows(kind: GapKind): List<GapRow> {
        install()
        scans++
        return store.read {
            driver.executeQuery(
                null, "SELECT kind, key, release_id, body, fetched_ms FROM $TABLE WHERE kind = ? ORDER BY key",
                { c -> QueryResult.Value(buildList { while (c.next().value) add(read(c)) }) }, 1,
            ) { bindString(0, kind.tag) }.value
        }
    }

    /** L-7 step 2 for an entry: a positive row parsed, else null (a negative row is a miss here too). */
    fun entry(seq: Long): DictEntryDetail? = row(GapRef.entry(seq))?.body?.let { runCatching { DictJson.entry(it) }.getOrNull() }

    fun kanji(literal: String): KanjiDetail? = row(GapRef.kanji(literal))?.body?.let { runCatching { DictJson.kanji(it) }.getOrNull() }

    /** Stores [rows] in one transaction (replacing rows with the same key). */
    fun put(rows: List<GapRow>) {
        if (rows.isEmpty()) return
        install()
        store.transaction {
            for (r in rows) {
                driver.execute(
                    null, "INSERT OR REPLACE INTO $TABLE (kind, key, release_id, body, fetched_ms) VALUES (?, ?, ?, ?, ?)", 5,
                ) {
                    bindString(0, r.ref.kind.tag); bindString(1, r.ref.key); bindLong(2, r.releaseId)
                    bindString(3, r.body); bindLong(4, r.fetchedMs)
                }
            }
        }
        generation++
    }

    fun delete(refs: Collection<GapRef>) {
        if (refs.isEmpty()) return
        install()
        store.transaction {
            for (r in refs) {
                driver.execute(null, "DELETE FROM $TABLE WHERE kind = ? AND key = ?", 2) {
                    bindString(0, r.kind.tag); bindString(1, r.key)
                }
            }
        }
        generation++
    }

    /** The last known server dictionary release (L-7 "Authoritative"), or null before the first answer. */
    fun serverReleaseId(): Long? = store.read { it.syncState.get(SERVER_RELEASE_KEY) }?.toLongOrNull()

    /**
     * Records the server's current release (from `getDictRelease` / `getMeta`). When it changed, every negative result
     * of another release is dropped (L-7: never permanent); positive rows of an older release stay readable and gap
     * fill refreshes them.
     */
    fun recordServerRelease(id: Long) {
        install()
        store.transaction { tx ->
            if (tx.syncState.get(SERVER_RELEASE_KEY) == id.toString()) return@transaction
            tx.syncState.put(SERVER_RELEASE_KEY, id.toString())
            driver.execute(null, "DELETE FROM $TABLE WHERE body IS NULL AND release_id <> ?", 1) { bindLong(0, id) }
        }
    }

    /** The counts of the last gap-fill run, or null before the first. */
    fun counts(): GapFillCounts? = store.read { tx ->
        val c = tx.syncState.get(CACHED_KEY)?.toIntOrNull()
        val m = tx.syncState.get(MISSING_KEY)?.toIntOrNull()
        if (c == null || m == null) null else GapFillCounts(c, m)
    }

    /** One more reference cached by the background queue (between runs): `gap_fill.cached` + 1, `missing` - 1 (not below 0). */
    internal fun countLanded() = store.transaction { tx ->
        val c = tx.syncState.get(CACHED_KEY)?.toIntOrNull() ?: 0
        val m = tx.syncState.get(MISSING_KEY)?.toIntOrNull() ?: 0
        tx.syncState.put(CACHED_KEY, (c + 1).toString())
        tx.syncState.put(MISSING_KEY, maxOf(0, m - 1).toString())
    }

    internal fun state(key: String): String? = store.read { it.syncState.get(key) }

    internal fun putState(values: Map<String, String>) = store.transaction { tx -> values.forEach { (k, v) -> tx.syncState.put(k, v) } }

    /** Runs [sql] (one result column) on the learning database. */
    internal fun <T> column(sql: String, read: (SqlCursor) -> T): List<T> = store.read {
        driver.executeQuery(null, sql, { c -> QueryResult.Value(buildList { while (c.next().value) add(read(c)) }) }, 0).value
    }

    private fun read(c: SqlCursor): GapRow {
        val kind = if (c.getString(0) == GapKind.KANJI.tag) GapKind.KANJI else GapKind.ENTRY
        return GapRow(GapRef(kind, c.getString(1).orEmpty()), c.getLong(2) ?: 0L, c.getString(3), c.getLong(4) ?: 0L)
    }

    companion object {
        const val TABLE: String = "dict_gap_cache"
        const val SERVER_RELEASE_KEY: String = "dict.server_release_id"
        internal const val CACHED_KEY = "dict.gapfill.cached"
        internal const val MISSING_KEY = "dict.gapfill.missing"
        internal const val STAMP_KEY = "dict.gapfill.stamp"
        internal const val RETRY_KEY = "dict.gapfill.retry"
        internal const val CHECKED_KEY = "dict.gapfill.checked_ms"

        private const val DDL = "CREATE TABLE IF NOT EXISTS $TABLE (kind TEXT NOT NULL, key TEXT NOT NULL, " +
            "release_id INTEGER NOT NULL, body TEXT, fetched_ms INTEGER NOT NULL, PRIMARY KEY (kind, key))"
    }
}
