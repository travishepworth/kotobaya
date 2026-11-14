package kotobaya.android.ankiapi.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.apply.LearningTable
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-TAKO-PERF: times the AnkiDroid `csum` duplicate query on a copy of a real device database.
 * Opt-in: set `KOTOBAYA_BENCH_DB` to a `kotobaya-app.db` copy (never committed). The dictionary is synthetic
 * (one entry per note `entry_seq` plus filler up to 60k, the device package size), so no private text is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DuplicateQueryBenchmarkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun csumQueryOnARealDeviceDatabase() {
        val src = System.getenv("KOTOBAYA_BENCH_DB")?.let(::File)
        assumeTrue("KOTOBAYA_BENCH_DB not set", src?.isFile == true)
        val db = app.getDatabasePath("bench.db").also { it.parentFile?.mkdirs() }
        checkNotNull(src).copyTo(db, overwrite = true)
        val d = RoboDevice(app, name = "bench.db")
        val seqs = d.store.read { tx ->
            tx.learning.where(LearningTable.NOTE, "type", JsonString("vocab"))
                .mapNotNull { (it["entry_seq"] as? JsonNumber)?.value?.toLong() }
        }.toSortedSet()
        val filler = generateSequence(1_000_000L) { it + 1 }.filter { it !in seqs }.take((60_000 - seqs.size).coerceAtLeast(0))
        val entries = (seqs.asSequence() + filler).map { Fx(it, "語$it", "ご$it", listOf("gloss $it", "more $it"), (it % 50_000).toInt()) }.toList()
        val dir = File.createTempFile("benchdict", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        PackageFixtures.create(File(dir, "p.db"), 1, "top60k", entries)
        File(dir, "p.db").copyTo(store.installed)
        val dict = InstalledDictionary(store)

        val hitWord = "語${seqs.first()}"
        val mid = AnkiIds.TAKOBOTO_MODEL_ID
        val s = takobotoStore(d, memoryMap(app), entries = InstalledEntryLookup(dict))
        fun timed(label: String, csum: Long): Int {
            val t0 = System.nanoTime()
            val n = s.findNotes(NoteFilter(mid, setOf(csum))).size
            println("BENCH $label: ${(System.nanoTime() - t0) / 1_000_000} ms, $n rows (${seqs.size} entry-linked notes)")
            return n
        }
        timed("miss cold", 567_984_260L)
        timed("miss warm", 567_984_260L)
        assertEquals(1, timed("hit", AnkiContract.fieldChecksum(hitWord)))

        // reference: what the query did per note before AND-TAKO-PERF
        fun phase(label: String, block: () -> Unit) {
            val t0 = System.nanoTime()
            block()
            println("BENCH per-note $label: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        val map = memoryMap(app)
        phase("vocab scan") { d.store.read { tx -> tx.learning.where(LearningTable.NOTE, "type", JsonString("vocab")).size } }
        phase("link lookups") { seqs.forEach { map.noteByKotobayaId("x$it") } }
        phase("dictionary entry reads") { seqs.forEach { dict.entry(it) } }
    }
}
