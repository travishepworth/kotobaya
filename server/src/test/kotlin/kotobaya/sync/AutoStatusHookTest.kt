package kotobaya.sync

import com.fasterxml.jackson.databind.JsonNode
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType.*
import kotobaya.server.json
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.merge.CoreCardCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** F-5: the server's auto_status hook derives what the core device applier derives (displayed status per entry). */
class AutoStatusHookTest : SyncFixture() {
    override fun syncConfig() = SyncConfig(clock = { syncNow.get() }, cardCache = CoreCardCache(), autoStatus = CoreAutoStatusHook())

    private val base = 0x0193a1b2c000L
    private val day = 86_400_000L
    private var n = 0

    private fun id(kind: Int) = "0193a1b2-000$kind-7000-8000-%012d".format(++n)

    private fun noteOp(hlc: Long, id: String, seq: Long) =
        op(NOTE_CREATED, A, hlc, """{"id":"$id","note_type_id":"00000000-0000-7000-8000-000000000101","entry_seq":$seq,"fields":{"meaning":"w$seq","notes":""}}""")

    private fun cardOp(hlc: Long, id: String, note: String) = op(CARD_CREATED, A, hlc, """{"id":"$id","note_id":"$note","kind":"meaning"}""")

    private fun reviewOp(hlc: Long, card: String, at: Long, rating: Int) =
        op(REVIEW_LOGGED, A, hlc, """{"id":"${id(3)}","card_id":"$card","reviewed_at":$at,"utc_offset_min":0,"review_type":"scheduled","rating":$rating}""")

    @Test
    fun serverAndDeviceDisplayTheSameStatusForEveryAutoStatus() {
        val ops = mutableListOf<JsonNode>()
        var h = base
        fun next() = ++h
        // 1: note, no card (new). 2: card never reviewed (new). 3: one review (learning). 4: easy reviews to 21d+ (known).
        // 5: learning with an explicit override (known). 6: known entry later reviewed Again (back to learning).
        val notes = (1..6).associateWith { id(1) }
        val cards = (2..6).associateWith { id(2) }
        for (e in 1..6) ops.add(noteOp(next(), notes.getValue(e), 1000L + e))
        for (e in 2..6) ops.add(cardOp(next(), cards.getValue(e), notes.getValue(e)))
        ops.add(reviewOp(next(), cards.getValue(3), base, 3))
        for (e in listOf(4, 6)) for (i in 0 until 6) ops.add(reviewOp(next(), cards.getValue(e), base + i * 10 * day, 4))
        ops.add(reviewOp(next(), cards.getValue(5), base, 3))
        ops.add(op(WORD_STATUS_SET, A, next(), """{"entry_seq":1005,"status":"known","origin":"manual"}"""))
        ops.add(reviewOp(next(), cards.getValue(6), base + 80 * day, 1))

        syncNow.set(java.time.Instant.ofEpochMilli(base + 100 * day))
        val a = principal(A)
        for (o in ops) assertAccepted(push(a, o).single())

        val device = InMemoryLearningTables.seeded()
        val applier = DeviceOpApplier<InMemoryLearningTables>()
        for (o in ops) {
            val tree = kotobaya.sync.DefaultCanonicalJson.decode(json.writeValueAsString(o)).value as JsonObject
            val decoded = assertIs<OpDecodeResult.Known>(DefaultOpCodec.decodeTree(tree, OpSource.PUSHED))
            applier.applyTo(device, decoded.op)
        }

        val deviceShown = (1001L..1006L).associateWith { e ->
            val row = device.get(LearningTable.WORD_STATUS, listOf(JsonNumber(e.toDouble())))
            (row?.get("status") as? JsonString)?.value ?: "new"
        }
        val serverShown = (1001L..1006L).associateWith { e ->
            db.txBlocking { it.fetchOptional("select status from app.word_status where entry_seq = ?", e).map { r -> r.get(0, String::class.java) }.orElse("new") }
        }
        assertEquals(deviceShown, serverShown)
        // the scenario really covers each auto status
        assertEquals("new", serverShown.getValue(1001))
        assertEquals("new", serverShown.getValue(1002))
        assertEquals("learning", serverShown.getValue(1003))
        assertEquals("known", serverShown.getValue(1004))
        assertEquals("known", serverShown.getValue(1005))
        assertEquals("learning", serverShown.getValue(1006))
        val auto = db.txBlocking { dsl -> dsl.fetch("select entry_seq, auto_status from app.word_status").associate { it.get(0, Long::class.java) to it.get(1, String::class.java) } }
        assertEquals("learning", auto[1005L], "the override does not hide the derived status")
        assertTrue(1001L !in auto && 1002L !in auto, "new entries keep no row")
    }
}
