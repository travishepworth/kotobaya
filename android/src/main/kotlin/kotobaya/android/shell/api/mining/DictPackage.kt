package kotobaya.android.shell.api.mining

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.PackageFile

/** `dict_release` of the installed package (DICT_PACKAGE 2.3). */
internal data class PackageRelease(val id: Long, val version: String, val builtAtMs: Long, val scope: String, val sources: String)

/** One `freq_list` row with the number of entries the package ranks by it. */
internal data class PackageFreqList(val id: String, val title: String, val priority: Int, val entryCount: Long)

/** One `entry_kanji` hit: a live entry containing the literal and its frequency sort key. */
internal data class KanjiEntryKey(val seq: Long, val freqKey: Int)

/** A Tatoeba pair of the package (`example_sentence`). */
internal data class PackageExample(val id: Long, val text: String, val translation: String)

/**
 * The installed package's tables the mining group reads beyond `InstalledDictionary` (which owns search and entry
 * details): the release row, frequency lists, `entry_kanji`, Tatoeba examples. Read-only (`SQLITE_OPEN_READONLY`),
 * reopened when the installer swaps the file ([DictStore.generation]); every read answers "nothing" when no package is
 * installed. Never touches the network.
 */
internal class DictPackage(private val store: DictStore) {
    private var handle: SQLiteDatabase? = null
    private var handleGeneration = -1L

    private fun <T> withDb(block: (SQLiteDatabase) -> T): T? = synchronized(this) {
        val gen = store.generation.value
        if (handle != null && (gen != handleGeneration || handle?.isOpen != true || !store.installed.isFile)) {
            handle?.close()
            handle = null
        }
        if (handle == null) {
            if (!store.installed.isFile) return null
            handle = PackageFile.openReadOnly(store.installed)
            handleGeneration = gen
        }
        block(handle ?: return null)
    }

    private inline fun <T> Cursor.rows(map: (Cursor) -> T): List<T> = use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

    fun release(): PackageRelease? = withDb { db ->
        db.rawQuery("SELECT id, version, built_at, scope, sources FROM dict_release", null).rows {
            PackageRelease(it.getLong(0), it.getString(1), it.getLong(2), it.getString(3), it.getString(4) ?: "{}")
        }.singleOrNull()
    }

    fun freqLists(): List<PackageFreqList> = withDb { db ->
        db.rawQuery(
            "SELECT l.id, l.title, l.priority, (SELECT count(*) FROM entry_freq f WHERE f.list_id = l.id) FROM freq_list l ORDER BY l.priority, l.id",
            null,
        ).rows { PackageFreqList(it.getString(0), it.getString(1), it.getInt(2), it.getLong(3)) }
    } ?: emptyList()

    /** Live (`retired = 0`) entries with an `entry_kanji` row for [literal]; frequency key = `freq_rank`, NULL last. */
    fun kanjiEntries(literal: String): List<KanjiEntryKey> = withDb { db ->
        db.rawQuery(
            "SELECT e.seq, coalesce(e.freq_rank, ${Int.MAX_VALUE}) FROM entry_kanji k JOIN dict_entry e ON e.seq = k.seq " +
                "WHERE k.literal = ? AND e.retired = 0",
            arrayOf(literal),
        ).rows { KanjiEntryKey(it.getLong(0), it.getInt(1)) }
    } ?: emptyList()

    /** Tatoeba pairs of [seq], shortest text first (code points), then id, at most [limit] (server `EntryExamplesApi`). */
    fun examples(seq: Long, limit: Int): List<PackageExample> = (
        withDb { db ->
            db.rawQuery(
                "SELECT s.id, s.text, s.translation FROM example_sentence_entry x JOIN example_sentence s ON s.id = x.sentence_id WHERE x.seq = ?",
                arrayOf(seq.toString()),
            ).rows { PackageExample(it.getLong(0), it.getString(1), it.getString(2)) }
        } ?: emptyList()
        ).sortedWith(compareBy<PackageExample>({ it.text.codePointCount(0, it.text.length) }, { it.id })).take(limit)
}

private val packages = java.util.WeakHashMap<DictStore, DictPackage>()

/** The one [DictPackage] per [DictStore] (its open handle is shared by every request). */
internal fun packageOf(store: DictStore): DictPackage = synchronized(packages) { packages.getOrPut(store) { DictPackage(store) } }
