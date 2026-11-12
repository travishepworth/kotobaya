package kotobaya.android.ankiapi.provider

import android.app.Application
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Production mode: no store attached to the provider, only what [AnkiStoreHolder] supplies. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnkiProviderStoreResolutionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val noteValues = ContentValues().apply {
        put(AnkiContract.Note.MID, InMemoryAnkiStore.BASIC_MODEL_ID)
        put(AnkiContract.Note.FLDS, "食べる\u001fto eat")
    }

    @Before
    fun setUp() {
        AnkiStoreHolder.reset()
        AnkiStoreHolder.waitMillis = 50
        Robolectric.setupContentProvider(AnkiProvider::class.java, AnkiContract.AUTHORITY)
        shadowOf(app).grantPermissions(AnkiContract.READ_WRITE_PERMISSION)
        ProviderCallLog.clear()
    }

    @After
    fun tearDown() {
        AnkiStoreHolder.reset()
        ProviderCallLog.clear()
    }

    @Test
    fun withNoStoreWritesAreRefusedNotFaked() {
        val r = app.contentResolver
        assertFailsWith<IllegalStateException> { r.insert(AnkiContract.NOTES_URI, noteValues) }
        assertFailsWith<IllegalStateException> { r.bulkInsert(AnkiContract.NOTES_URI, arrayOf(noteValues)) }
        assertFailsWith<IllegalStateException> { r.query(AnkiContract.NOTES_URI, null, null, null, null) }
        assertTrue(ProviderCallLog.snapshot().any { "no AnkiStore" in it })
        // Unknown URIs still answer empty without a store.
        val unknown = android.net.Uri.withAppendedPath(AnkiContract.AUTHORITY_URI, "schedule")
        r.query(unknown, null, null, null, null)!!.use { assertEquals(0, it.count) }
    }

    @Test
    fun storeSetByTheAppIsUsed() {
        val store = InMemoryAnkiStore()
        AnkiStoreHolder.set(store)
        val uri = app.contentResolver.insert(AnkiContract.NOTES_URI, noteValues)
        assertEquals("content://com.ichi2.anki.flashcards/notes/1000", uri.toString())
        assertEquals(1, store.findNotes(NoteFilter()).size)
    }

    @Test
    fun storeSetDuringColdStartIsAwaited() {
        AnkiStoreHolder.waitMillis = 5_000
        val store = InMemoryAnkiStore()
        val setter = Thread {
            Thread.sleep(100)
            AnkiStoreHolder.set(store)
        }.apply { start() }
        app.contentResolver.insert(AnkiContract.NOTES_URI, noteValues)
        setter.join()
        assertEquals(1, store.findNotes(NoteFilter()).size)
    }

    @Test
    fun unpermittedCallerAddsOneShortLineWithoutItsArguments() {
        shadowOf(app).denyPermissions(AnkiContract.READ_WRITE_PERMISSION)
        val secret = "x".repeat(1000)
        assertFailsWith<SecurityException> {
            app.contentResolver.call(AnkiContract.AUTHORITY_URI, secret, secret, null)
        }
        assertFailsWith<SecurityException> {
            app.contentResolver.insert(AnkiContract.NOTES_URI, ContentValues().apply { put("flds", secret) })
        }
        val lines = ProviderCallLog.snapshot()
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines.all { "refused" in it })
        assertFalse(lines.any { secret in it })
    }
}
