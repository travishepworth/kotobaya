package kotobaya.android.ankiapi.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.NewNote
import kotobaya.android.ankiapi.provider.NoteFilter
import kotobaya.android.ankiapi.provider.ProviderCallLog
import kotobaya.android.notes.CreateResult
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.DEVICE_B
import kotobaya.android.notes.NoteRequest
import kotobaya.android.notes.T0
import kotobaya.android.sync.TestServer
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-TAKO-PERF2: the `csum` duplicate query and the duplicate word rule answer from device-only indexes, and stay
 * correct when notes are added, edited (first field changed) and deleted on another device and arrive by sync
 * replay: both before the index catches up (pending changes) and after. Each answer is compared with the old
 * full scan (a store without indexes on the same database).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NoteIndexReplayTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val mid = AnkiIds.TAKOBOTO_MODEL_ID

    @Before
    fun clearLog() = ProviderCallLog.clear()

    private class Phone(val app: Application, server: TestServer) {
        val d = RoboDevice(app, server, deviceId = DEVICE_A)
        val idx = Indexes(d)
        val store = indexedStore(d, memoryMap(app), indexes = idx)
        val scan = takobotoStore(d, memoryMap(app))
    }

    private fun Phone.answer(word: String): List<Pair<String, List<String>>> =
        store.findNotes(NoteFilter(mid, setOf(AnkiContract.fieldChecksum(word)))).map { it.guid to it.fields }

    /** The indexed answer for [word] equals the scan's, and is [expected] (note ids). */
    private fun Phone.check(word: String, expected: List<String>, label: String) {
        val scanned = scan.findNotes(NoteFilter(mid, setOf(AnkiContract.fieldChecksum(word)))).map { it.guid to it.fields }
        val indexed = answer(word)
        assertEquals(scanned, indexed, "$label: indexed vs scan for $word")
        assertEquals(expected, indexed.map { it.first }, "$label: $word")
        assertTrue(ProviderCallLog.snapshot().last { "csum query" in it }.contains("csum query: index"), "$label: answered from the index")
    }

    private fun sync(vararg devices: RoboDevice) = devices.forEach { assertIs<CycleResult.Completed>(it.sync()) }

    private fun created(r: CreateResult): String = assertIs<CreateResult.Created>(r).noteId

    @Test
    fun csumLookupStaysCorrectAfterNotesAreAddedEditedAndDeletedBySyncReplay() {
        val server = TestServer(T0)
        val phone = Phone(app, server)
        val other = RoboDevice(app, server, deviceId = DEVICE_B).also { it.clock.now = T0 + 1_000 }
        val local = created(noteCreator(phone.d, seed = 3).create(NoteRequest(word = "食べる", reading = "たべる")))
        assertTrue(phone.store.warmIndexes() >= 1)
        phone.check("食べる", listOf(local), "local note")

        // added elsewhere: an entry-linked note (first field from the dictionary) and an unmatched one
        val neko = created(noteCreator(other, seed = 4).create(NoteRequest(word = "猫", reading = "ねこ")))
        val hoge = created(noteCreator(other, seed = 5).create(NoteRequest(word = "ほげほげ")))
        sync(other, phone.d)
        phone.check("猫", listOf(neko), "added, not caught up")
        phone.check("ほげほげ", listOf(hoge), "added unmatched, not caught up")
        phone.store.warmIndexes()
        phone.check("猫", listOf(neko), "added, caught up")
        phone.check("ほげほげ", listOf(hoge), "added unmatched, caught up")

        // edited elsewhere: the first field changes
        update(other, neko, "子猫")
        sync(other, phone.d)
        phone.check("猫", emptyList(), "old first field, not caught up")
        phone.check("子猫", listOf(neko), "new first field, not caught up")
        phone.store.warmIndexes()
        phone.check("猫", emptyList(), "old first field, caught up")
        phone.check("子猫", listOf(neko), "new first field, caught up")

        // deleted elsewhere
        assertIs<MintResult.Minted>(other.minter.mint(OpType.NOTE_DELETED, JsonObject(mapOf("id" to JsonString(neko)))))
        sync(other, phone.d)
        phone.check("子猫", emptyList(), "deleted, not caught up")
        phone.store.warmIndexes()
        phone.check("子猫", emptyList(), "deleted, caught up")
        phone.check("食べる", listOf(local), "untouched note")
    }

    @Test
    fun duplicateWordRuleFollowsReplayedEdits() {
        val server = TestServer(T0)
        val phone = Phone(app, server)
        val other = RoboDevice(app, server, deviceId = DEVICE_B).also { it.clock.now = T0 + 1_000 }
        phone.store.warmIndexes()
        val hoge = created(noteCreator(other, seed = 5).create(NoteRequest(word = "ほげほげ")))
        sync(other, phone.d)
        val dup = phone.store.add(NewNote(mid, listOf("ほげほげ", "", "", "", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto"))
        assertEquals(hoge, assertIs<AddOutcome.Duplicate>(dup).noteId, "replayed unmatched note is found by its word")

        update(other, hoge, "ふがふが")
        sync(other, phone.d)
        val again = phone.store.add(NewNote(mid, listOf("ふがふが", "", "", "", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto"))
        assertEquals(hoge, assertIs<AddOutcome.Duplicate>(again).noteId, "found by its new word")
        val freed = phone.store.add(NewNote(mid, listOf("ほげほげ", "", "", "", ""), emptyList(), AnkiIds.INBOX_DECK_ID, "jp.takoboto"))
        assertIs<AddOutcome.Added>(freed, "the old word is free again")
    }

    @Test
    fun droppedTriggersAreReinstalledAndTheIndexRebuilt() {
        val phone = Phone(app, TestServer(T0))
        val a = created(noteCreator(phone.d, seed = 3).create(NoteRequest(word = "食べる", reading = "たべる")))
        phone.store.warmIndexes()
        assertTrue(phone.idx.csums.usable())
        phone.d.driver.execute(null, "DROP TRIGGER device_note_change_upd", 0)
        assertFalse(phone.idx.log.installed())
        assertFalse(phone.idx.csums.usable(), "an index cannot trust a log that missed changes")
        val b = created(noteCreator(phone.d, seed = 4).create(NoteRequest(word = "猫", reading = "ねこ")))
        phone.check("猫", listOf(b), "after reinstall")
        phone.check("食べる", listOf(a), "after reinstall")
        assertTrue(phone.idx.log.installed())
    }

    @Test
    fun aRebuildPreparedOutsideTheTransactionKeepsChangesMadeMeanwhile() {
        val phone = Phone(app, TestServer(T0))
        val a = created(noteCreator(phone.d, seed = 3).create(NoteRequest(word = "食べる", reading = "たべる")))
        val index = phone.idx.csums
        phone.d.store.transaction { index.install() }
        val prepared = phone.d.store.read { index.prepare(it.learning) }
        assertTrue(prepared != null, "a rebuild is due")
        val b = created(noteCreator(phone.d, seed = 4).create(NoteRequest(word = "猫", reading = "ねこ"))) // after the read
        phone.d.store.transaction { index.catchUp(it.learning, prepared) }
        assertEquals(phone.idx.log.head(), phone.idx.log.cursor(ChecksumIndex.CONSUMER)?.seq, "nothing pending")
        assertEquals(listOf(b), index.candidates(setOf("expression"), setOf(AnkiContract.fieldChecksum("猫"))), "from the index itself")
        assertEquals(listOf(a), index.candidates(setOf("expression"), setOf(AnkiContract.fieldChecksum("食べる"))))
        assertEquals(null, phone.d.store.read { index.prepare(it.learning) }, "no rebuild due any more")
    }

    @Test
    fun aMissReadsOnlyCandidateRows() {
        val phone = Phone(app, TestServer(T0))
        for ((i, w) in listOf("食べる", "猫", "上手", "飲む", "見る").withIndex()) {
            noteCreator(phone.d, seed = 10 + i).create(NoteRequest(word = w))
        }
        phone.store.warmIndexes()
        phone.check("猫", phone.answer("猫").map { it.first }, "hit")
        assertTrue(ProviderCallLog.snapshot().last { "csum query" in it }.contains("rows=1 "), ProviderCallLog.snapshot().last())
        phone.check("ないもの", emptyList(), "miss")
        assertTrue(ProviderCallLog.snapshot().last { "csum query" in it }.contains("rows=0 "), ProviderCallLog.snapshot().last())
    }

    private fun update(d: RoboDevice, noteId: String, expression: String) {
        val payload = JsonObject(
            mapOf(
                "id" to JsonString(noteId),
                "set" to JsonObject(mapOf("fields" to JsonObject(mapOf("expression" to JsonString(expression))))),
            ),
        )
        assertIs<MintResult.Minted>(d.minter.mint(OpType.NOTE_UPDATED, payload))
    }
}
