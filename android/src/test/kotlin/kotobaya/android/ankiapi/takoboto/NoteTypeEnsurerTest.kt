package kotobaya.android.ankiapi.takoboto

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotobaya.android.ankiapi.store.dev
import kotobaya.android.ankiapi.store.ensurer
import kotobaya.android.ankiapi.store.pendingOps
import kotobaya.android.notes.DEVICE_A
import kotobaya.android.notes.DEVICE_B
import kotobaya.android.notes.T0
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.serverReplica
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult

class NoteTypeEnsurerTest {
    private fun takobotoTypes(t: LearningTables) = t.all(LearningTable.NOTE_TYPE).filter { it["name"] == JsonString(TakobotoVocab.NAME) }

    @Test
    fun theFixedIdIsDerivedFromNameAndKind() {
        val h = MessageDigest.getInstance("SHA-256").digest("kotobaya:note_type:vocab:${TakobotoVocab.NAME}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32).toCharArray()
        h[12] = '8'
        h[16] = "%x".format(8 or (h[16].digitToInt(16) and 3))[0]
        val s = String(h)
        assertEquals("${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}", TakobotoVocab.NOTE_TYPE_ID)
    }

    @Test
    fun creationIsIdempotentOnRepeatedAppStarts() {
        val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
        assertEquals(EnsureResult.Ready(TakobotoVocab.NOTE_TYPE_ID, created = true), ensurer(d.dev()).ensure())
        assertEquals(EnsureResult.Ready(TakobotoVocab.NOTE_TYPE_ID, created = false), ensurer(d.dev()).ensure())
        repeat(3) {
            d.restart()
            assertEquals(EnsureResult.Ready(TakobotoVocab.NOTE_TYPE_ID, created = false), ensurer(d.dev(), seed = it + 10).ensure())
        }
        assertEquals(1, pendingOps(d.dev()).count { it.type == OpType.NOTE_TYPE_CREATED.wire })
        assertEquals(1, takobotoTypes(d.tables()).size)
    }

    @Test
    fun twoDevicesCreatingItOfflineConvergeOnOneRow() {
        val server = TestServer(T0)
        val a = SqlDevice(DEVICE_A, server, T0, seed = 1)
        val b = SqlDevice(DEVICE_B, server, T0 + 1_000, seed = 2)
        a.transport.offline = true
        b.transport.offline = true
        assertIs<EnsureResult.Ready>(ensurer(a.dev(), 1).ensure())
        assertIs<EnsureResult.Ready>(ensurer(b.dev(), 2).ensure())
        a.transport.offline = false
        b.transport.offline = false
        assertIs<CycleResult.Completed>(a.sync())
        assertIs<CycleResult.Completed>(b.sync())
        assertIs<CycleResult.Completed>(a.sync())
        for (t in listOf(a.tables(), b.tables(), serverReplica(server))) {
            assertEquals(listOf(JsonString(TakobotoVocab.NOTE_TYPE_ID)), takobotoTypes(t).map { it["id"] })
        }
        // and neither mints again
        assertEquals(EnsureResult.Ready(TakobotoVocab.NOTE_TYPE_ID, created = false), ensurer(a.dev()).ensure())
        assertEquals(EnsureResult.Ready(TakobotoVocab.NOTE_TYPE_ID, created = false), ensurer(b.dev()).ensure())
    }

    @Test
    fun aLiveTypeOfThatNameMadeElsewhereIsReused() {
        val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
        val other = "0193a1b2-0009-7000-8000-000000000001"
        assertIs<MintResult.Minted>(d.stack.minter.mint(OpType.NOTE_TYPE_CREATED, TakobotoVocab.createPayload(other)))
        assertEquals(EnsureResult.Ready(other, created = false), ensurer(d.dev()).ensure())
    }

    @Test
    fun aDeletedFixedTypeIsReplacedByAFreshOne() {
        val d = SqlDevice(DEVICE_A, TestServer(T0), T0)
        ensurer(d.dev()).ensure()
        val del = kotobaya.contracts.sync.JsonObject(mapOf("id" to JsonString(TakobotoVocab.NOTE_TYPE_ID)))
        assertIs<MintResult.Minted>(d.stack.minter.mint(OpType.NOTE_TYPE_DELETED, del))
        val r = assertIs<EnsureResult.Ready>(ensurer(d.dev()).ensure())
        assertEquals(true, r.created)
        assertEquals(false, r.noteTypeId == TakobotoVocab.NOTE_TYPE_ID)
        assertEquals(EnsureResult.Ready(r.noteTypeId, created = false), ensurer(d.dev()).ensure())
    }
}
