package kotobaya.android.search

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.db.app.AppDatabase
import kotobaya.android.dict.DictStore
import kotobaya.android.dict.Fx
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.dict.PackageFixtures
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** C-24 on device: a bare term finds entry-linked notes with empty fields through the offline dictionary's forms. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DictFormsSearchTest {
    private val driver = AndroidSqliteDriver(AppDatabase.Schema, ApplicationProvider.getApplicationContext<Application>(), null)
    private val db = AppDatabase(driver)

    private fun uuid(kind: Int, n: Int) = "0193a1b2-%04d-7000-8000-%012d".format(kind, n)

    private fun linkedNote(n: Int, entry: Long) {
        driver.execute(
            null,
            "INSERT INTO note (id, note_type_id, type, entry_seq, fields, created_by_device_id, created_at, updated_at) " +
                "VALUES ('${uuid(1, n)}', '00000000-0000-7000-8000-000000000101', 'vocab', $entry, '{}', 'dev', $n, $n)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO card (id, note_id, kind, suspended, lapses, created_at, updated_at) VALUES ('${uuid(2, n)}', '${uuid(1, n)}', 'meaning', 0, 0, $n, $n)",
            0,
        )
    }

    private fun dictionary(): InstalledDictionary {
        val dir = File.createTempFile("dictforms", "").also { it.delete(); it.mkdirs() }
        val store = DictStore(File(dir, "store"))
        PackageFixtures.create(
            File(dir, "p.db"), 1, "top60k",
            listOf(Fx(1, "食べる", "たべる", listOf("to eat"), 100), Fx(2, "飲む", "のむ", listOf("to drink"), 120)),
        )
        File(dir, "p.db").copyTo(store.installed)
        return InstalledDictionary(store)
    }

    private fun ids(search: LocalCardSearch, q: String) =
        (search.search(q) as CardSearchOutcome.Results).hits.map { it.cardId }

    @Test
    fun bareTermMatchesTheDictionaryFormsOfAnEntryLinkedNoteWithEmptyFields() {
        linkedNote(1, 1)
        linkedNote(2, 2)
        val withDict = LocalCardSearch(driver, db, { NOW }, dictionary())
        assertEquals(listOf(uuid(2, 1)), ids(withDict, "食べる"))
        assertEquals(listOf(uuid(2, 1)), ids(withDict, "たべる"))
        assertEquals(listOf(uuid(2, 2)), ids(withDict, "のむ"))
        // a one-character kana is a normal, bounded lookup (both entries contain る / む only once each)
        assertEquals(listOf(uuid(2, 1)), ids(withDict, "る"))
    }

    @Test
    fun aPatternMatchingMoreEntriesThanTheCapDropsTheDictionaryBranch() {
        linkedNote(1, 1)
        linkedNote(2, 2)
        val dict = dictionary()
        // cap 1: "る" matches one entry (kept), "む" too; a pattern hitting both entries (ま is in neither) -- use cap 0
        assertEquals(listOf(uuid(2, 1)), ids(LocalCardSearch(driver, db, { NOW }, dict, dictFormsMaxSeqs = 1), "食べる"))
        assertTrue(ids(LocalCardSearch(driver, db, { NOW }, dict, dictFormsMaxSeqs = 0), "食べる").isEmpty())
    }

    @Test
    fun withoutADictionaryBehaviourIsUnchanged() {
        linkedNote(1, 1)
        val none = LocalCardSearch(driver, db, { NOW })
        assertTrue(ids(none, "食べる").isEmpty())
        val notInstalled = LocalCardSearch(driver, db, { NOW }, InstalledDictionary(DictStore(File.createTempFile("none", "").also { it.delete() })))
        assertTrue(ids(notInstalled, "食べる").isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
