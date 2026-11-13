package kotobaya.android.ankiapi.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiStoreException
import kotobaya.android.ankiapi.provider.NewModel
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.ankiapi.takoboto.TakobotoVocab
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.NoteRequest
import kotobaya.android.notes.T0
import kotobaya.android.notes.creator
import kotobaya.android.notes.isLive
import kotobaya.android.notes.notes
import kotobaya.android.notes.userDeck
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TakobotoAnkiStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mid = AnkiIds.TAKOBOTO_MODEL_ID

    private fun device() = SqlDevice(DEVICE_A, TestServer(T0), T0)

    private fun word(w: String, r: String, meaning: String = "", deck: Long? = AnkiIds.INBOX_DECK_ID, caller: String? = "jp.takoboto") =
        NewNote(mid, listOf(w, r, meaning, "", ""), listOf("takoboto"), deck, caller)

    @Test
    fun duplicateQueryMissThenHitAndTheSameWordTwiceCreatesOneNote() {
        val d = device()
        val s = takobotoStore(d, memoryMap(app))
        val csum = AnkiContract.fieldChecksum("食べる")
        assertEquals(emptyList(), s.findNotes(NoteFilter(mid, setOf(csum))))
        assertEquals(emptyList(), s.findNotes(NoteFilter(sortField = "食べる")))

        val first = assertIs<AddOutcome.Added>(s.add(word("食べる", "たべる", "to eat")))
        val second = assertIs<AddOutcome.Duplicate>(s.add(word("食べる", "たべる")))
        assertEquals(first.noteId, second.noteId)
        assertEquals(first.note.id, second.note.id, "the duplicate answers with the existing note's Anki id")
        assertEquals(1, notes(d.tables()).count(::isLive))
        assertEquals(1, pendingOps(d).count { it.type == OpType.NOTE_CREATED.wire })

        assertEquals(listOf(first.note.id), s.findNotes(NoteFilter(mid, setOf(csum))).map { it.id })
        assertEquals(listOf(first.note.id), s.findNotes(NoteFilter(sortField = "食べる")).map { it.id })
        assertEquals(listOf(first.note.id), s.findNotes(NoteFilter(sortField = "たべる")).map { it.id }, "same word by its reading")
        assertEquals(emptyList(), s.findNotes(NoteFilter(mid, setOf(AnkiContract.fieldChecksum("猫")))))
        assertEquals(emptyList(), s.findNotes(NoteFilter(sortField = "猫")))
    }

    @Test
    fun aMatchedNoteMadeElsewhereIsADuplicateByChecksumOfItsHeadword() {
        val d = device()
        // created on this device outside the AnkiDroid API: entry only, no fields.expression
        val made = assertIs<CreateResult.Created>(creator(d).create(NoteRequest(word = "猫", reading = "ねこ")))
        val s = takobotoStore(d, memoryMap(app))
        val hits = s.findNotes(NoteFilter(mid, setOf(AnkiContract.fieldChecksum("猫"))))
        assertEquals(listOf(made.noteId), hits.map { it.guid })
        assertEquals(listOf("猫", "ねこ", "cat", "", ""), hits.single().fields, "dictionary fills what the note does not store")
        assertIs<AddOutcome.Duplicate>(s.add(word("猫", "ねこ")))
    }

    @Test
    fun takobotoCallerGivesOriginTakoboto() {
        val d = device()
        takobotoStore(d, memoryMap(app)).add(word("猫", "ねこ", caller = "jp.takoboto"))
        takobotoStore(d, memoryMap(app), seed = 9).add(word("食べ物", "たべもの", caller = "com.example.other"))
        val origins = pendingOps(d).filter { it.type == OpType.NOTE_CREATED.wire }.map { (it.payload.members["origin"] as JsonString).value }
        assertEquals(listOf("takoboto", "api"), origins)
    }

    @Test
    fun userDecksReplaceTheInboxAndAddingToOneMintsADeckItem() {
        val d = device()
        val deckId = userDeck(d, "0193a1b2-0004-7000-8000-000000000001")
        val s = takobotoStore(d, memoryMap(app))
        val decks = s.decks()
        assertEquals(listOf("Mined"), decks.map { it.name })
        assertEquals(decks.single().id, s.selectedDeckId())
        s.add(word("猫", "ねこ", deck = decks.single().id))
        val put = pendingOps(d).single { it.type == OpType.DECK_ITEM_PUT.wire }.payload.members
        assertEquals(JsonString(deckId), put["deck_id"])
        assertEquals(JsonString("e:1489780"), put["item_key"])
        // the inbox id stays accepted (deckless); an unknown deck is refused
        assertIs<AddOutcome.Added>(s.add(word("食べ物", "たべもの", deck = AnkiIds.INBOX_DECK_ID)))
        assertFailsWith<AnkiStoreException.NotFound> { s.add(word("食べる", "たべる", deck = 999_999)) }
    }

    @Test
    fun addDeckMintsAUserDeckOnceByName() {
        val d = device()
        val s = takobotoStore(d, memoryMap(app))
        val a = s.addDeck("Anime", null)
        assertEquals(a, s.addDeck("Anime", null))
        assertEquals(listOf("Anime"), s.decks().map { it.name })
        assertEquals(1, pendingOps(d).count { it.type == OpType.DECK_CREATED.wire })
    }

    @Test
    fun clientModelFieldsAreMappedByNameAndUnknownFieldsAreKeptInNotes() {
        val d = device()
        val s = takobotoStore(d, memoryMap(app))
        val m = s.addModel(
            NewModel("Takoboto", listOf("Japanese", "Reading", "Meaning", "Sentence", "Pitch"), 1, "", null, 0, 0, null, null, "jp.takoboto"),
        )
        assertEquals(m, s.addModel(NewModel("Takoboto", m.fields, 1, "", null, 0, 0, null, null, null)), "same name and fields: same model")
        assertTrue(m.id > mid)
        val added = assertIs<AddOutcome.Added>(
            s.add(NewNote(m.id, listOf("食べる", "たべる", "to eat", "", "た↓べる"), emptyList(), null, "jp.takoboto")),
        )
        val fields = (pendingOps(d).single { it.type == OpType.NOTE_CREATED.wire }.payload.members["fields"] as JsonObject).members
        assertEquals(JsonString("食べる"), fields["expression"])
        assertEquals(JsonString("to eat"), fields["meaning"])
        assertEquals(JsonString("Pitch: た↓べる"), fields[TakobotoVocab.OVERFLOW_KEY])
        assertEquals(m.id, s.note(added.note.id)!!.modelId)
        assertFailsWith<AnkiStoreException.Invalid> { s.add(NewNote(m.id, listOf("x"), emptyList(), null, null)) }
    }

    @Test
    fun ankiIdsAreStableAcrossRestarts() {
        val file = File(app.filesDir, "ankiapi-restart.db").also { it.delete() }
        val d = device()
        val first = takobotoStore(d, SqliteAnkiIdMap(app, file.path)).add(word("猫", "ねこ")).note.id
        d.restart()
        val s = takobotoStore(d, SqliteAnkiIdMap(app, file.path), seed = 8)
        assertEquals(first, s.findNotes(NoteFilter(mid, setOf(AnkiContract.fieldChecksum("猫")))).single().id)
        assertEquals("猫", s.note(first)!!.fields[0])
        assertEquals(2, s.cards(first).size)
    }

    @Test
    fun unpairedDeviceRefusesTheAddAndSavesNothing() {
        val d = device()
        d.store.transaction { it.syncState.remove(kotobaya.sync.client.store.SyncStateKeys.DEVICE_ID) }
        val s = takobotoStore(d, memoryMap(app))
        assertFailsWith<IllegalStateException> { s.add(word("猫", "ねこ")) }
        assertEquals(0, notes(d.tables()).size)
        assertEquals(emptyList(), pendingOps(d))
    }
}
