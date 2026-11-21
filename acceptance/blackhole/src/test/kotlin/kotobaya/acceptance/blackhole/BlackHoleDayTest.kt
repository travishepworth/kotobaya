package kotobaya.acceptance.blackhole

import android.app.Application
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.notes.isLive
import kotobaya.android.notes.str
import kotobaya.android.shell.parity.ParityDictionary
import kotobaya.android.sync.TestServer
import kotobaya.android.sync.assertSameRows
import kotobaya.android.sync.ensureSqliteJdbc
import kotobaya.android.sync.projection
import kotobaya.android.sync.serverReplica
import kotobaya.android.notes.mergeEmitter
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpDecodeResult
import kotobaya.contracts.sync.OpSource
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultOpCodec
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.protocol.CycleResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LF-X1 (ADR-050, SHELL.md L-9, SYNC.md 11-13): a scripted day on two phones whose network is a black hole -- (a) never
 * answers, (b) answers after 8 s, (c) drops every second request -- against the real [kotobaya.android.shell.api.LocalApi]
 * with a virtual clock, then the network heals and both phones and the server sync.
 *
 * Phone A reviews 60 cards (new cards of a deck's entries by Keep going), searches, reads dictionary entries, edits a
 * note and bulk-tags. Phone B is offline for its whole day and reviews 15 cards from the same deck, so both
 * materialize the same entries and the server has to merge them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BlackHoleDayTest {
    class Outcome(val mode: NetMode, val a: SimPhone, val b: SimPhone, val dayA: Day, val dayB: Day, val server: TestServer, val healed: List<CycleResult>)

    companion object {
        const val T0 = 1_795_168_800_000L
        const val DEVICE_A = "01931f3e-0000-7000-8000-0000000000d4"
        const val DEVICE_B = "01931f3e-0000-7000-8000-0000000000d5"
        const val SEED_DEVICE = "01931f3e-0000-7000-8000-0000000000a1"
        const val NOTE_TYPE = "00000000-0000-7000-8000-000000000101"
        const val DECK = "0193a1b2-0004-7000-8000-000000000001"
        const val GOAL = 5
        const val A_ANSWERS = 60
        const val B_ANSWERS = 15
        private val cache = HashMap<NetMode, Outcome>()

        /** The notes that exist before the day; every other dictionary entry is a deck entry without a note. */
        const val EXISTING_NOTES = 12

        fun outcome(mode: NetMode): Outcome = cache.getOrPut(mode) { runDay(mode) }

        private fun uuid(kind: Int, n: Int) = "0193a1b2-%04x-7000-8000-%012x".format(kind, n)

        private fun jo(vararg m: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*m))

        /** The shared start: a deck of entries and some notes, on the server's log (as if authored on another device). */
        private fun seed(server: TestServer) {
            val seqs = ParityDictionary.fixture.seqs.sorted()
            var i = 0
            fun put(type: String, entityType: String, entityId: String, payload: JsonObject) {
                val tree = jo(
                    "op_id" to JsonString(uuid(0xff, ++i)),
                    "op_type" to JsonString(type),
                    "op_version" to JsonNumber(1.0),
                    "entity_type" to JsonString(entityType),
                    "entity_id" to JsonString(entityId),
                    "device_id" to JsonString(SEED_DEVICE),
                    "hlc" to JsonString("%012x-0000-%s".format(T0 - 3_600_000 + i, SEED_DEVICE)),
                    "payload" to payload,
                )
                val op = (DefaultOpCodec.decodeTree(tree, OpSource.PUSHED) as OpDecodeResult.Known).op
                server.append(op, null)
            }
            put("settings.set", "settings", "scheduler.new_per_day", jo("key" to JsonString("scheduler.new_per_day"), "value" to JsonNumber(GOAL.toDouble())))
            put("deck.created", "deck", DECK, jo("id" to JsonString(DECK), "name" to JsonString("Core words"), "type" to JsonString("user")))
            seqs.forEachIndexed { n, seq ->
                if (n < EXISTING_NOTES) {
                    val note = uuid(1, n + 1)
                    put("note.created", "note", note, jo("id" to JsonString(note), "note_type_id" to JsonString(NOTE_TYPE), "entry_seq" to JsonNumber(seq.toDouble())))
                    val card = uuid(2, n + 1)
                    put("card.created", "card", card, jo("id" to JsonString(card), "note_id" to JsonString(note), "kind" to JsonString("meaning")))
                } else {
                    put(
                        "deck.item_put", "deck_item", "$DECK|e:$seq",
                        jo("deck_id" to JsonString(DECK), "item_key" to JsonString("e:$seq"), "position" to JsonNumber(1024.0 * (n - EXISTING_NOTES + 1))),
                    )
                }
            }
        }

        private fun runDay(mode: NetMode): Outcome {
            ensureSqliteJdbc()
            val server = TestServer(T0)
            server.emitAfter = mergeEmitter(server)
            seed(server)
            val netA = BlackHoleNet(NetMode.HEALTHY)
            val netB = BlackHoleNet(NetMode.HEALTHY)
            val a = SimPhone(DEVICE_A, server, T0, netA, seed = 1)
            val b = SimPhone(DEVICE_B, server, T0 + 5_000, netB, seed = 2)
            // first sync of both phones, on a good network (the one network wait that remains, ADR-050)
            assertTrue(a.sync() is CycleResult.Completed, "first sync of A")
            assertTrue(b.sync() is CycleResult.Completed, "first sync of B")

            netA.mode = mode
            netB.mode = mode
            val dayA = Day(a, A_ANSWERS, syncMidDay = true, seed = 11)
            dayA.run()
            // B never gets a cycle during its day: it is the phone in the tunnel
            val dayB = Day(b, B_ANSWERS, syncMidDay = false, seed = 22)
            dayB.run()

            // heal
            netA.mode = NetMode.HEALTHY
            netB.mode = NetMode.HEALTHY
            server.nowMs = maxOf(a.clock.nowMs(), b.clock.nowMs()) + 1_000
            val healed = ArrayList<CycleResult>()
            repeat(3) {
                healed += a.sync()
                healed += b.sync()
            }
            return Outcome(mode, a, b, dayA, dayB, server, healed)
        }
    }

    // ---- acceptance bullet 1: zero network wait on the UI path, modes a-c

    private fun noUiWait(mode: NetMode) {
        val o = outcome(mode)
        for ((name, day, phone) in listOf(Triple("A", o.dayA, o.a), Triple("B", o.dayB, o.b))) {
            assertEquals(emptyList(), day.problems, "$mode phone $name: UI requests that waited for or used the network")
            assertEquals(0L, phone.net.uiWaitedMs, "$mode phone $name: virtual network wait on the UI path")
            assertEquals(emptyList(), phone.net.uiCalls, "$mode phone $name: upstream calls from the UI path")
            assertTrue(day.uiRequests > 100, "$mode phone $name: the script ran (${day.uiRequests} requests)")
        }
        assertEquals(A_ANSWERS, o.dayA.answered.size, "$mode: phone A answered 60 cards")
        assertEquals(B_ANSWERS, o.dayB.answered.size, "$mode: phone B answered its cards")
        assertTrue(o.dayA.keepGoingPresses > 0, "$mode: Keep going was pressed to get new cards from deck entries")
        // the network really was bad for the background sync of A (which tried mid-day)
        when (mode) {
            NetMode.NEVER -> assertTrue(o.a.net.syncDropped > 0)
            NetMode.SLOW_8S -> assertTrue(o.a.net.syncWaitedMs >= 8_000)
            NetMode.DROP_EVERY_SECOND -> assertTrue(o.a.net.syncDropped > 0)
            NetMode.HEALTHY -> Unit
        }
    }

    @Test
    fun noUiPathRequestWaitsForTheNetworkWhenNothingAnswers() = noUiWait(NetMode.NEVER)

    @Test
    fun noUiPathRequestWaitsForTheNetworkWhenAnswersTakeEightSeconds() = noUiWait(NetMode.SLOW_8S)

    @Test
    fun noUiPathRequestWaitsForTheNetworkWhenEverySecondRequestIsDropped() = noUiWait(NetMode.DROP_EVERY_SECOND)

    // ---- acceptance bullet 2: convergence after healing

    private fun converges(mode: NetMode) {
        val o = outcome(mode)
        assertTrue(o.healed.all { it is CycleResult.Completed }, "$mode: every cycle after healing completed: ${o.healed}")
        val reference = projection(serverReplica(o.server))
        assertSameRows(reference, projection(o.a.tables()), "$mode: device A vs server")
        assertSameRows(reference, projection(o.b.tables()), "$mode: device B vs server")
        assertSameRows(projection(o.a.tables()), projection(o.b.tables()), "$mode: A vs B")

        // every answer present exactly once, on the server and on both devices
        val expected = (o.dayA.answered + o.dayB.answered)
        assertEquals(expected.size, expected.toSet().size, "review ids are unique")
        val serverReviews = o.server.loggedOps().filter { it.opType == OpType.REVIEW_LOGGED }.map { it.entityId }
        assertEquals(expected.sorted(), serverReviews.sorted(), "$mode: server log holds each answer exactly once")
        for ((name, phone) in listOf("A" to o.a, "B" to o.b)) {
            val rows = phone.tables().all(LearningTable.REVIEW_LOG).map { str(it, "id") }
            assertEquals(expected.sorted(), rows.sorted(), "$mode: device $name review_log holds each answer exactly once")
        }

        // the outbox is empty: nothing is left to push
        for ((name, phone) in listOf("A" to o.a, "B" to o.b)) {
            val pending = phone.store.read { tx -> kotobaya.sync.client.store.PendingState.PENDING.let { tx.outbox.inState(it) } }
            assertEquals(0, pending.size, "$mode: device $name has ops still pending")
        }

        // materialized duplicates are merged: one live note per entry everywhere
        val merges = o.server.loggedOps().count { it.opType == OpType.NOTE_MERGED }
        assertTrue(merges > 0, "$mode: both phones materialized the same entries, so the server had to merge (got $merges merges)")
        for ((name, t) in listOf("server" to serverReplica(o.server), "A" to o.a.tables(), "B" to o.b.tables())) {
            val live = t.all(LearningTable.NOTE).filter { isLive(it) && it["entry_seq"] is JsonNumber }
            val bySeq = live.groupBy { (it["entry_seq"] as JsonNumber).value.toLong() }
            val dup = bySeq.filterValues { it.size > 1 }.keys
            assertEquals(emptySet(), dup, "$mode: $name has more than one live note for entries $dup")
            assertTrue(bySeq.size > EXISTING_NOTES, "$mode: $name has notes materialized from deck entries (${bySeq.size})")
        }
    }

    @Test
    fun convergesAfterHealingWhenNothingAnswered() = converges(NetMode.NEVER)

    @Test
    fun convergesAfterHealingWhenAnswersTookEightSeconds() = converges(NetMode.SLOW_8S)

    @Test
    fun convergesAfterHealingWhenEverySecondRequestWasDropped() = converges(NetMode.DROP_EVERY_SECOND)

    // ---- acceptance bullet 3: loop invariants on every queue response

    private fun invariants(mode: NetMode) {
        val o = outcome(mode)
        assertEquals(emptyList(), o.dayA.loopViolations + o.dayB.loopViolations, "$mode: loop invariants I1-I4")
        assertTrue(o.dayA.queueResponses + o.dayB.queueResponses > 70, "$mode: queue responses were checked")
    }

    @Test
    fun loopInvariantsHoldOnEveryQueueResponseWhenNothingAnswered() = invariants(NetMode.NEVER)

    @Test
    fun loopInvariantsHoldOnEveryQueueResponseWhenAnswersTookEightSeconds() = invariants(NetMode.SLOW_8S)

    @Test
    fun loopInvariantsHoldOnEveryQueueResponseWhenEverySecondRequestWasDropped() = invariants(NetMode.DROP_EVERY_SECOND)
}
