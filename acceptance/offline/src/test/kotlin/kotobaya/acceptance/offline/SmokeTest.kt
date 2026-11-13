package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.protocol.CycleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmokeTest {
    @Test
    fun oneNoteRoundTrip() {
        SimServer.create(WEEK_START).use { server ->
            val world = World()
            val a = SimDevice("A", "01931f3e-0000-7000-8000-0000000000a1", server, world, 1)
            val note = a.ids.newOpId(world.now)
            val card = a.ids.newOpId(world.now)
            assertIs<MintResult.Minted>(
                a.mint(
                    MintRequest(OpType.NOTE_CREATED, obj("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to 1358280)),
                    MintRequest(OpType.CARD_CREATED, obj("id" to card, "note_id" to note, "kind" to "meaning")),
                ),
            )
            world.now += MINUTE
            server.nowMs = world.now
            assertIs<MintResult.Minted>(
                a.mint(MintRequest(OpType.REVIEW_LOGGED, obj("id" to a.ids.newOpId(world.now), "card_id" to card, "reviewed_at" to a.clock.nowMs(), "utc_offset_min" to 540, "review_type" to "learning", "rating" to 3, "button" to "good"))),
            )
            assertIs<CycleResult.Completed>(a.sync())
            val log = server.log()
            assertEquals(3, log.size)
            val ref = projection(replicaOf(log))
            assertEquals(emptyList(), diffRows(ref, projection(a.tables())))
            assertEquals(emptyList(), diffRows(ref, server.projection(ref)))
            println("smoke ok on ${server.kind}")
        }
    }
}
