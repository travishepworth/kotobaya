package kotobaya.android.ankiconnect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.ankiapi.store.RoboDevice
import kotobaya.android.ankiapi.store.countOf
import kotobaya.android.ankiapi.store.memoryMap
import kotobaya.android.ankiapi.store.pendingOps
import kotobaya.android.ankiapi.store.takobotoStore
import kotobaya.contracts.sync.OpType
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real store behind the protocol: notes become ops, duplicates are refused, nothing is written by a check. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoreAnkiConnectBackendTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun call(p: AnkiConnectProtocol, json: String) = JSONObject(p.handle(json))

    @Test
    fun addNoteMintsOpsAndTheSecondAddIsTheDuplicateError() {
        val d = RoboDevice(app)
        val store = takobotoStore(d, memoryMap(app))
        val media = mutableMapOf<String, Int>()
        val p = AnkiConnectProtocol(StoreAnkiConnectBackend(store, MediaSink { n, b -> media[n] = b.size; n })) { null }

        val decks = call(p, """{"action":"deckNames","version":6}""").getJSONArray("result")
        assertEquals(1, decks.length()) // the inbox until the user has a deck
        val deck = decks.getString(0)
        val models = call(p, """{"action":"modelNames","version":6}""").getJSONArray("result")
        assertEquals("Takoboto Vocab", models.getString(0))
        val note = """{"deckName":"$deck","modelName":"Takoboto Vocab","fields":{"Expression":"食べる","Kana":"たべる","Meaning":"to eat","Sentence":"毎日食べる"}}"""

        val can = call(p, """{"action":"canAddNotes","version":6,"params":{"notes":[$note]}}""")
        assertTrue(can.getJSONArray("result").getBoolean(0))
        assertEquals(0, countOf(pendingOps(d), OpType.NOTE_CREATED)) // a check writes nothing

        val added = call(p, """{"action":"addNote","version":6,"params":{"note":$note}}""")
        assertTrue(added.isNull("error"), added.toString())
        val id = added.getLong("result")
        assertEquals(1, countOf(pendingOps(d), OpType.NOTE_CREATED))

        val again = call(p, """{"action":"addNote","version":6,"params":{"note":$note}}""")
        assertEquals("cannot create note because it is a duplicate", again.getString("error"))
        assertEquals(1, countOf(pendingOps(d), OpType.NOTE_CREATED))
        assertEquals(false, call(p, """{"action":"canAddNotes","version":6,"params":{"notes":[$note]}}""").getJSONArray("result").getBoolean(0))

        val found = call(p, """{"action":"findNotes","version":6,"params":{"query":"deck:$deck \"Expression:食べる\""}}""").getJSONArray("result")
        assertEquals(listOf(id), (0 until found.length()).map { found.getLong(it) })
        val info = call(p, """{"action":"notesInfo","version":6,"params":{"notes":[$id]}}""").getJSONArray("result").getJSONObject(0)
        assertEquals("食べる", info.getJSONObject("fields").getJSONObject("Expression").getString("value"))
        assertIs<Any>(info.getJSONObject("fields").getJSONObject("Sentence"))
    }

    @Test
    fun unknownDeckModelAndFieldAreErrors() {
        val d = RoboDevice(app)
        val p = AnkiConnectProtocol(StoreAnkiConnectBackend(takobotoStore(d, memoryMap(app))) { n, _ -> n }) { null }
        val deck = call(p, """{"action":"deckNames","version":6}""").getJSONArray("result").getString(0)
        fun err(n: String) = call(p, """{"action":"addNote","version":6,"params":{"note":$n}}""").getString("error")
        assertEquals("deck was not found: Nope", err("""{"deckName":"Nope","modelName":"Takoboto Vocab","fields":{"Expression":"猫"}}"""))
        assertEquals("model was not found: Nope", err("""{"deckName":"$deck","modelName":"Nope","fields":{"Expression":"猫"}}"""))
        assertTrue(err("""{"deckName":"$deck","modelName":"Takoboto Vocab","fields":{"Bogus":"猫"}}""").contains("Bogus"))
        assertEquals("cannot create note because it is empty", err("""{"deckName":"$deck","modelName":"Takoboto Vocab","fields":{"Expression":""}}"""))
        assertEquals(0, countOf(pendingOps(d), OpType.NOTE_CREATED))
    }
}
