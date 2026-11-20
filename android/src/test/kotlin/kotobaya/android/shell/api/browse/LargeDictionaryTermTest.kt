package kotobaya.android.shell.api.browse

import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import kotobaya.android.shell.api.actions.ActionsTestSupport
import kotobaya.android.shell.api.actions.CardQueryResult
import kotobaya.android.shell.api.actions.LocalCardQuery
import kotobaya.android.shell.api.browse.BrowseTestSupport.ids
import kotobaya.android.shell.api.browse.BrowseTestSupport.next
import kotobaya.android.shell.parity.ParityDevice
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-A5: a text term matching more than 50 000 dictionary entries (a one-kana pattern over a full package), with more
 * collection entries matching it than the old device guard (3000) allowed, is answered by `listCards` on the device
 * within the unit-test timeout and pages stably: every matching card exactly once, in sort order, also when a card is
 * created between two pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class LargeDictionaryTermTest {
    private val base = 2_000_000L

    /** Readings `る` + three hiragana: [ENTRIES] distinct entries, all containing `る`; plus [OTHER] that do not. */
    private fun reading(i: Int): String = "る" + (0 until 3).map { k -> Char(0x3041 + (i / pow(80, k)) % 80) }.joinToString("")

    private fun pow(b: Int, e: Int): Int = (0 until e).fold(1) { a, _ -> a * b }

    private fun dictionary(d: ParityDevice): InstalledDictionary {
        val dir = File.createTempFile("bigdict", "").also { it.delete(); it.mkdirs() }
        val file = PackageFixtures.create(File(dir, "p.db"), 1, "all", listOf(Fx(1, "食べる", "たべる", listOf("to eat"), 100)))
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            val entry = db.compileStatement("INSERT INTO dict_entry VALUES (?, 'ja', ?, ?, 1, '[]', ?, '[]', '[\"n\"]', '[]', NULL, 0)")
            val form = db.compileStatement("INSERT INTO dict_entry_form VALUES (?, 'reading', 0, ?, ?, 1)")
            fun put(seq: Long, r: String) {
                entry.bindLong(1, seq); entry.bindString(2, r); entry.bindString(3, r); entry.bindString(4, """[{"text":"$r"}]""")
                entry.executeInsert()
                form.bindLong(1, seq); form.bindString(2, r); form.bindString(3, r)
                form.executeInsert()
            }
            for (i in 0 until ENTRIES) put(base + i, reading(i))
            for (i in 0 until OTHER) put(base + ENTRIES + i, "かき" + Char(0x3041 + i % 40) + Char(0x3041 + i / 40))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
        val store = DictStore(File(dir, "store"))
        file.copyTo(store.installed)
        return InstalledDictionary(store, null, d.gapCache)
    }

    private fun card(i: Int) = "0193e000-0002-7000-8000-%012x".format(i)

    /** A vocab note linked to [seq] (empty fields: only the dictionary form can match) with one card, created at [at]. */
    private fun linked(d: ParityDevice, i: Int, seq: Long, at: Long) {
        val note = "0193e000-0001-7000-8000-%012x".format(i)
        d.driver.execute(
            null,
            "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at) " +
                "VALUES ('$note', '${BrowseTestSupport.VOCAB_TYPE}', 'vocab', $seq, '{}', 'dev', $at, $at)",
            0,
        )
        d.driver.execute(null, "INSERT INTO card (id, note_id, kind, created_at, updated_at) VALUES ('${card(i)}', '$note', 'meaning', $at, $at)", 0)
    }

    @Test
    fun aTermMatchingMoreThan50000EntriesAnswersQuicklyAndPagesStably() {
        Class.forName("org.sqlite.JDBC")
        val d = ParityDevice(ActionsTestSupport.X, ActionsTestSupport.T0, routes, withDictionary = false)
        d.pull(ActionsTestSupport.seed(), "seed")
        d.clock.now = ActionsTestSupport.T0 + 10_000
        val dict = dictionary(d)
        // the collection: COLLECTION matching entries spread over the package, plus non-matching ones
        val expected = HashSet<String>()
        for (i in 0 until COLLECTION) {
            linked(d, i, base + i * (ENTRIES / COLLECTION), 1_000L + i % 977)
            expected += card(i)
        }
        for (i in 0 until OTHER) linked(d, COLLECTION + i, base + ENTRIES + i, 5_000L)

        // the legacy guard gave up on exactly this query; without the guard, core's compiler selects the reference set
        assertTrue(LocalCardQuery(d.driver, d.database, dict).select("る", d.clock.now) is CardQueryResult.Forward)
        val reference = LocalCardQuery(d.driver, d.database, dict, dictFormsMaxSeqs = Int.MAX_VALUE).select("る", d.clock.now)
        assertTrue(reference is CardQueryResult.Ids && reference.ids.containsAll(expected), "the reference selects every linked card")
        val selected = reference.ids.toHashSet()
        val order = ArrayList<String>()
        d.driver.executeQuery(
            null, "SELECT id FROM card ORDER BY created_at, id",
            { c -> while (c.next().value) c.getString(0)?.takeIf { it in selected }?.let(order::add); QueryResult.Unit }, 0,
        )

        val api = LocalSearchApi(d, dict)
        val seen = ArrayList<String>()
        var cursor: String? = null
        var pages = 0
        var slowest = 0L
        do {
            val started = System.nanoTime()
            val res = api.cards("る", "sort=created_at&limit=500" + (cursor?.let { "&cursor=$it" } ?: ""))
            slowest = maxOf(slowest, (System.nanoTime() - started) / 1_000_000)
            assertEquals(200, res.status, res.body)
            seen += ids(res)
            cursor = next(res)
            pages++
            // a matching card created between pages, sorting before the cursor, neither repeats nor hides another
            if (pages == 1) linked(d, COLLECTION + OTHER + 1, base + 7, 0L)
        } while (cursor != null)

        assertEquals(seen.size, seen.toSet().size, "no card repeats across pages")
        assertEquals(order, seen, "every matching card exactly once, in sort order; the non-matching entries never")
        assertEquals((order.size + 499) / 500, pages)
        assertTrue(slowest < PAGE_BUDGET_MS, "slowest page took $slowest ms")
    }

    private companion object {
        const val ENTRIES = 60_000
        const val OTHER = 200
        const val COLLECTION = 4_000
        const val PAGE_BUDGET_MS = 10_000L
    }
}
