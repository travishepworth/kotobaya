package kotobaya.android.ankiapi.store

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import kotobaya.android.notes.InstalledCandidateSource
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-TAKO-PERF2: a whole Takoboto send (duplicate query miss, bulkInsert of one note, duplicate query hit) on a
 * copy of a real device database, at 1x and at 4x its notes and cards. Opt-in: `KOTOBAYA_BENCH_DB` = a
 * `kotobaya-app.db` copy (never committed). The dictionary is synthetic (one entry per note `entry_seq` plus filler
 * up to 60k), so no private text is used. `KOTOBAYA_BENCH_PROFILE=1` also prints a stack-sample profile of an insert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TakobotoSendBenchmarkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun takobotoSendOnARealDeviceDatabase() {
        val src = System.getenv("KOTOBAYA_BENCH_DB")?.let(::File)
        assumeTrue("KOTOBAYA_BENCH_DB not set", src?.isFile == true)
        val one = run(checkNotNull(src), scale = 1)
        val four = run(src, scale = 4)
        for (k in one.keys) println("BENCH ${k.padEnd(18)} 1x ${one[k]} ms   4x ${four[k]} ms")
    }

    /** Medians (ms) of each step of a send, on the device copy with every note (and card) copied [scale] times. */
    private fun run(src: File, scale: Int): Map<String, Long> {
        val db = app.getDatabasePath("bench-$scale.db").also { it.parentFile?.mkdirs() }
        src.copyTo(db, overwrite = true)
        if (scale > 1) inflate(db, scale)
        val d = RoboDevice(app, name = db.name)
        d.clock.now = System.currentTimeMillis() // the copy's HLC is past the test epoch
        val seqs = d.store.read { tx ->
            tx.learning.where(LearningTable.NOTE, "type", JsonString("vocab"))
                .mapNotNull { (it["entry_seq"] as? JsonNumber)?.value?.toLong() }
        }.toSortedSet()
        val filler = generateSequence(100_000_000L) { it + 1 }.filter { it !in seqs }
            .take((60_000 - seqs.size).coerceAtLeast(ITER)).toList()
        val entries = (seqs.asSequence() + filler).map { Fx(it, "語$it", "ご$it", listOf("gloss $it"), (it % 50_000).toInt()) }.toList()
        val dir = File.createTempFile("benchdict", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        PackageFixtures.create(File(dir, "p.db"), 1, "top60k", entries)
        File(dir, "p.db").copyTo(store.installed)
        val dict = InstalledDictionary(store)
        val s = indexedStore(d, memoryMap(app), source = InstalledCandidateSource(dict), entries = InstalledEntryLookup(dict))
        val mid = AnkiIds.TAKOBOTO_MODEL_ID
        val times = LinkedHashMap<String, MutableList<Long>>()
        fun <T> timed(label: String, block: () -> T): T {
            val t0 = System.nanoTime()
            val r = block()
            times.getOrPut(label) { ArrayList() } += (System.nanoTime() - t0) / 1_000_000
            return r
        }
        s.ensureNoteType()
        timed("index build (once)") { s.warmIndexes() }
        timed("first query") { s.findNotes(NoteFilter(mid, setOf(1L))) }
        val profile = System.getenv("KOTOBAYA_BENCH_PROFILE") == "1"
        for (i in 0 until ITER) {
            // a dictionary word, then one the dictionary does not have (the unmatched path)
            val words = listOf(Triple("matched", "語${filler[i]}", "ご${filler[i]}"), Triple("unmatched", "未登録$i", "みとうろく$i"))
            for ((kind, word, reading) in words) {
                val csum = AnkiContract.fieldChecksum(word)
                timed("$kind miss") { s.findNotes(NoteFilter(mid, setOf(csum))) }
                val sampler = if (profile && i == ITER - 1) Sampler(Thread.currentThread()).also { it.start() } else null
                timed("$kind insert") {
                    s.transaction { s.addNote(NewNote(mid, listOf(word, reading, "", "", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto")) }
                }
                sampler?.stopAndPrint("$kind insert ${scale}x")
                val hit = timed("$kind hit") { s.findNotes(NoteFilter(mid, setOf(csum))) }
                check(hit.size == 1) { "hit returned ${hit.size}" }
            }
        }
        return times.mapValues { (_, v) -> v.sorted()[v.size / 2] }
    }

    /** Copies every note and card [scale] - 1 more times (new ids; vocab copies get entry seqs of their own). */
    private fun inflate(file: File, scale: Int) {
        val sql = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            fun cols(table: String) = sql.rawQuery("PRAGMA table_info($table)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }
            val noteCols = cols("note")
            val cardCols = cols("card")
            sql.beginTransaction()
            for (k in 1 until scale) {
                val n = noteCols.joinToString { col ->
                    when (col) {
                        "id" -> "id || '-$k'"
                        "entry_seq" -> "CASE WHEN entry_seq IS NULL THEN NULL ELSE entry_seq + ${k * 10_000_000L} END"
                        else -> col
                    }
                }
                sql.execSQL("INSERT INTO note (${noteCols.joinToString()}) SELECT $n FROM note WHERE id NOT LIKE '%-_'")
                val c = cardCols.joinToString { col ->
                    when (col) {
                        "id" -> "id || '-$k'"
                        "note_id" -> "note_id || '-$k'"
                        else -> col
                    }
                }
                sql.execSQL("INSERT INTO card (${cardCols.joinToString()}) SELECT $c FROM card WHERE id NOT LIKE '%-_'")
            }
            sql.setTransactionSuccessful()
        } finally {
            if (sql.inTransaction()) sql.endTransaction()
            sql.close()
        }
    }

    private companion object {
        const val ITER = 5
    }

    /** Samples [target]'s stack every millisecond; prints the hottest kotobaya frames. */
    private class Sampler(private val target: Thread) : Thread("bench-sampler") {
        @Volatile private var running = true
        private val counts = HashMap<String, Int>()
        private var total = 0

        override fun run() {
            while (running) {
                val st = target.stackTrace
                total++
                st.map { "${it.className}.${it.methodName}:${it.lineNumber}" }
                    .filter { it.startsWith("kotobaya.") && "Lambda" !in it && "Benchmark" !in it }.distinct()
                    .forEach { counts.merge(it, 1, Int::plus) }
                sleep(1)
            }
        }

        fun stopAndPrint(label: String) {
            running = false
            join()
            println("PROFILE $label samples=$total")
            counts.entries.sortedByDescending { it.value }.take(30).forEach { println("PROFILE ${it.value} ${it.key}") }
        }
    }
}
