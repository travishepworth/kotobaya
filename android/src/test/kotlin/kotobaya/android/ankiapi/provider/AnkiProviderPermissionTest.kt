package kotobaya.android.ankiapi.provider

import android.app.Application
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnkiProviderPermissionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = InMemoryAnkiStore()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(AnkiProvider::class.java, AnkiContract.AUTHORITY).attachStore(store)
        ProviderCallLog.clear()
    }

    private val noteValues = ContentValues().apply {
        put(AnkiContract.Note.MID, InMemoryAnkiStore.BASIC_MODEL_ID)
        put(AnkiContract.Note.FLDS, "食べる\u001fto eat")
    }

    @Test
    fun callerWithoutReadWritePermissionIsRefusedEverywhere() {
        shadowOf(app).denyPermissions(AnkiContract.READ_WRITE_PERMISSION)
        val r = app.contentResolver
        assertFailsWith<SecurityException> { r.query(AnkiContract.DECKS_URI, null, null, null, null) }
        assertFailsWith<SecurityException> { r.insert(AnkiContract.NOTES_URI, noteValues) }
        assertFailsWith<SecurityException> { r.bulkInsert(AnkiContract.NOTES_URI, arrayOf(noteValues)) }
        assertFailsWith<SecurityException> {
            r.update(
                android.net.Uri.withAppendedPath(AnkiContract.AUTHORITY_URI, "selected_deck"),
                ContentValues().apply { put(AnkiContract.Deck.DECK_ID, 1L) },
                null,
                null,
            )
        }
        assertFailsWith<SecurityException> { r.delete(android.net.Uri.withAppendedPath(AnkiContract.NOTES_URI, "1"), null, null) }
        assertFailsWith<SecurityException> { r.call(AnkiContract.AUTHORITY_URI, "x", null, null) }
        // Nothing reached the store, and the refusal is in the call log.
        assertTrue(store.findNotes(NoteFilter()).isEmpty())
        assertTrue(ProviderCallLog.snapshot().any { "refused" in it })
    }

    @Test
    fun callerWithPermissionIsServed() {
        shadowOf(app).grantPermissions(AnkiContract.READ_WRITE_PERMISSION)
        val uri = app.contentResolver.insert(AnkiContract.NOTES_URI, noteValues)
        assertEquals("content://com.ichi2.anki.flashcards/notes/1000", uri.toString())
        assertEquals(1, store.findNotes(NoteFilter()).size)
    }
}
