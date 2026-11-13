package kotobaya.android.ankiapi.takoboto

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiProvider
import kotobaya.android.ankiapi.provider.ProviderCallLog
import kotobaya.android.ankiapi.store.AnkiIds
import kotobaya.android.ankiapi.store.RoboDevice
import kotobaya.android.ankiapi.store.memoryMap
import kotobaya.android.ankiapi.store.pendingOps
import kotobaya.android.ankiapi.store.takobotoStore
import kotobaya.android.notes.NoteCreator
import kotobaya.android.notes.isLive
import kotobaya.android.notes.notes
import kotobaya.android.notes.str
import kotobaya.android.notes.stringList
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.store.PendingState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 'S01 call-log replay' against the REAL store: the seeded Takoboto session
 * (src/test/resources/ankiapi/takoboto/add_words.json) through a ContentResolver into [AnkiProvider]
 * backed by [kotobaya.android.ankiapi.store.TakobotoAnkiStore] on a SQLite device. The fixture asserts
 * the AnkiDroid-visible rows and URIs; this test asserts the notes, cards and ops behind them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TakobotoReplayTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(AnkiContract.READ_WRITE_PERMISSION)
        ProviderCallLog.clear()
    }

    @Test
    fun s01CallLogReplayCreatesTheExpectedNotesCardsAndOpsOffline() {
        val d = RoboDevice(app)
        val server = d.server
        d.transport.offline = true // no network during the whole session
        val map = memoryMap(app)
        Robolectric.setupContentProvider(AnkiProvider::class.java, AnkiContract.AUTHORITY).attachStore(takobotoStore(d, map))

        val saved = ProviderReplay(app.contentResolver).replay("ankiapi/takoboto/add_words.json")
        assertEquals(AnkiIds.TAKOBOTO_MODEL_ID.toString(), saved["mid"])

        // --- ops: in the outbox, in order, with the payloads Takoboto's fields map to
        val ops = pendingOps(d)
        val n = OpType.NOTE_CREATED.wire
        val c = OpType.CARD_CREATED.wire
        assertEquals(listOf(OpType.NOTE_TYPE_CREATED.wire, n, c, c, n, c, c, n, c, c), ops.map { it.type })
        assertEquals(TakobotoVocab.NOTE_TYPE_ID, ops[0].entityId)
        assertEquals(TakobotoVocab.createPayload(TakobotoVocab.NOTE_TYPE_ID), ops[0].payload)

        val taberu = ops[1].payload.members
        assertEquals(JsonString(TakobotoVocab.NOTE_TYPE_ID), taberu["note_type_id"])
        assertEquals(JsonNumber(1358280.0), taberu["entry_seq"])
        assertEquals(
            JsonObject(
                mapOf(
                    "expression" to JsonString("食べる"),
                    "reading" to JsonString("たべる"),
                    "meaning" to JsonString("to eat"),
                    "sentence" to JsonString("パンを食べる。"),
                    "sentence_translation" to JsonString("I eat bread."),
                ),
            ),
            taberu["fields"],
        )
        assertEquals(listOf("takoboto"), stringList(taberu["tags"]))
        assertEquals(JsonString("パンを食べる。"), taberu["source_sentence"])
        assertEquals(JsonString("api"), taberu["origin"], "Robolectric's caller is not Takoboto")
        assertEquals(listOf("meaning", "reading"), ops.subList(2, 4).map { (it.payload.members["kind"] as JsonString).value })
        ops.subList(2, 4).forEach { assertEquals(JsonString(ops[1].entityId), it.payload.members["note_id"]) }

        assertEquals(JsonNumber(1489780.0), ops[4].payload.members["entry_seq"]) // 猫
        val funya = ops[7].payload.members
        assertEquals(null, funya["entry_seq"], "unknown word: no entry")
        assertEquals(listOf("takoboto", NoteCreator.UNMATCHED_TAG), stringList(funya["tags"]))
        assertEquals(JsonString("ふにゃふにゃ"), (funya["fields"] as JsonObject).members["expression"])
        assertTrue(ops.none { it.type == OpType.DECK_ITEM_PUT.wire }, "the inbox is deckless")

        // --- local rows: three live notes (the duplicate created nothing), six cards
        val t = d.tables()
        val live = notes(t).filter(::isLive)
        assertEquals(3, live.size)
        assertEquals(setOf("食べる", "猫", "ふにゃふにゃ"), live.map { ((it["fields"] as JsonObject).members["expression"] as JsonString).value }.toSet())
        assertEquals(6, t.all(LearningTable.CARD).size)
        assertEquals(TakobotoVocab.NAME, str(t.get(LearningTable.NOTE_TYPE, listOf(JsonString(TakobotoVocab.NOTE_TYPE_ID)))!!, "name"))
        assertEquals(3, map.counter(AnkiIds.COUNT_ADDED))
        assertEquals(1, map.counter(AnkiIds.COUNT_DUPLICATE))

        // --- after a simulated sync they reach the test server
        assertTrue(server.log.isEmpty())
        d.transport.offline = false
        assertIs<CycleResult.Completed>(d.sync())
        assertEquals(ops.map { it.type }, server.log.map { (it.members["op_type"] as JsonString).value })
        assertEquals(0, d.store.read { it.outbox.count(PendingState.PENDING) })
    }
}
