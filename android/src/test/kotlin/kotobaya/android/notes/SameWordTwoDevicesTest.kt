package kotobaya.android.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotobaya.android.sync.SqlDevice
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.assertSameRows
import kotobaya.android.sync.projection
import kotobaya.android.sync.serverReplica
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.LearningTables
import kotobaya.sync.client.protocol.CycleResult

/** SYNC.md 12.3 through [NoteCreator]: the same word added offline on two devices converges on one survivor (8.1–8.3). */
class SameWordTwoDevicesTest {
    @Test
    fun bothDevicesConvergeOnOneSurvivor() {
        val server = TestServer(T0)
        server.emitAfter = mergeEmitter(server)
        val a = SqlDevice(DEVICE_A, server, T0, seed = 1)
        val b = SqlDevice(DEVICE_B, server, T0 + 5_000, seed = 2)
        a.transport.offline = true
        b.transport.offline = true

        // both offline: neither sees the other's note, so neither is a local duplicate
        val w = assertIs<CreateResult.Created>(creator(a, seed = 11).create(NoteRequest(word = "食べる", reading = "たべる", origin = "takoboto")))
        val l = assertIs<CreateResult.Created>(
            creator(b, seed = 22).create(NoteRequest(word = "食べる", reading = "たべる", sentence = "パンを食べる", origin = "takoboto", tags = listOf("anime"))),
        )
        assertEquals(1358280L, (l.match as MatchResult.Matched).entry.seq)

        a.transport.offline = false
        b.transport.offline = false
        assertIs<CycleResult.Completed>(a.sync())
        assertIs<CycleResult.Completed>(b.sync()) // B's note.created collides: the server merges it into A's
        assertIs<CycleResult.Completed>(a.sync())
        assertIs<CycleResult.Completed>(b.sync())

        val emitted = server.loggedOps().filter { it.opType == OpType.NOTE_MERGED || it.opType == OpType.CARD_MERGED }
        assertEquals(listOf(OpType.NOTE_MERGED, OpType.CARD_MERGED, OpType.CARD_MERGED), emitted.map { it.opType })

        val reference = projection(serverReplica(server))
        assertSameRows(reference, projection(a.tables()), "device A")
        assertSameRows(reference, projection(b.tables()), "device B")

        for ((name, t) in listOf("A" to a.tables(), "B" to b.tables())) {
            val live = notes(t).filter { isLive(it) && it["entry_seq"] == JsonNumber(1358280.0) }
            assertEquals(listOf(w.noteId), live.map { str(it, "id") }, "$name: one survivor, the note that held the entry first")
            val loser = t.get(LearningTable.NOTE, listOf(JsonString(l.noteId)))!!
            assertEquals(JsonString(w.noteId), loser["merged_into_id"], name)
            // the loser's values were forwarded to the survivor (8.2 step 1)
            val survivor = t.get(LearningTable.NOTE, listOf(JsonString(w.noteId)))!!
            assertEquals(listOf("anime"), stringList(survivor["tags"]), name)
            assertEquals(JsonString("パンを食べる"), survivor["source_sentence"], name)
            // each loser card points at the survivor's card of its kind
            assertCardsMerged(t, l.cardIds, w.noteId, name)
        }

        // after the merge B's duplicate check answers with the survivor
        assertEquals(w.noteId, duplicateCheck(b).find("食べる", "たべる")?.noteId)
        assertIs<CreateResult.Duplicate>(creator(b, seed = 23).create(NoteRequest(word = "食べる")))
    }

    private fun assertCardsMerged(t: LearningTables, loserCards: List<String>, survivorNote: String, ctx: String) {
        val survivorCards = cards(t, survivorNote).filter(::isLive).associateBy { str(it, "kind") }
        assertEquals(setOf("meaning", "reading"), survivorCards.keys, ctx)
        for (id in loserCards) {
            val c = t.get(LearningTable.CARD, listOf(JsonString(id)))!!
            assertEquals(survivorCards.getValue(str(c, "kind"))["id"], c["merged_into_id"], "$ctx: card $id")
        }
    }
}
