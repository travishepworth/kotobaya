package kotobaya.acceptance.offline

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.Op
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.protocol.CycleResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** A scripted run: a server, [n] devices with exact clocks, the [Sim] ledger and oracle. */
class Story(n: Int = 2, seed: Int = 1) : AutoCloseable {
    val world = World()
    val server = SimServer.create(WEEK_START, seed = seed)
    val devices = (0 until n).map { SimDevice("dev$it", Chaos.DEVICE_IDS[it], server, world, seed * 31 + it) }
    val sim = Sim(server, world, devices)
    val a: SimDevice get() = devices[0]
    val b: SimDevice get() = devices[1]

    init {
        sim.at(WEEK_START)
    }

    fun at(t: Long) = sim.at(t)

    fun tick(ms: Long) = sim.at(world.now + ms)

    /** Mints one op through the device's `OpMinter` (guards included); fails if refused. */
    fun mint(d: SimDevice, type: OpType, vararg pairs: Pair<String, Any?>): Op =
        assertNotNull(sim.mint(d, MintRequest(type, obj(*pairs))), "${d.name}: ${type.wire} refused").single()

    fun mintAll(d: SimDevice, vararg reqs: MintRequest): List<Op> = assertNotNull(sim.mint(d, *reqs), "${d.name}: refused")

    fun sync(d: SimDevice): CycleResult = assertNotNull(d.sync(), "${d.name}: killed")

    fun syncOk(d: SimDevice) = assertIs<CycleResult.Completed>(sync(d), d.name)

    /** A vocab note and its cards on [d]; returns (note id, card ids by kind). */
    fun word(d: SimDevice, entry: Long, vararg kinds: String = arrayOf("meaning"), extra: Map<String, Any?> = emptyMap()): Pair<String, Map<String, String>> {
        val note = d.ids.newOpId(d.clock.nowMs())
        val cards = kinds.associateWith { d.ids.newOpId(d.clock.nowMs()) }
        val payload = mapOf("id" to note, "note_type_id" to Reserved.NOTE_TYPE_VOCAB, "entry_seq" to entry) + extra
        mintAll(d, MintRequest(OpType.NOTE_CREATED, json(payload) as JsonObject), *cards.map { (k, id) -> MintRequest(OpType.CARD_CREATED, obj("id" to id, "note_id" to note, "kind" to k)) }.toTypedArray())
        return note to cards
    }

    fun review(d: SimDevice, card: String, rating: Int = 3, type: String = "scheduled"): Op = mint(
        d, OpType.REVIEW_LOGGED, "id" to d.ids.newOpId(d.clock.nowMs()), "card_id" to card, "reviewed_at" to d.clock.nowMs(),
        "utc_offset_min" to 540, "review_type" to type, "rating" to rating, "button" to listOf("again", "hard", "good", "easy")[rating - 1],
    )

    fun row(d: SimDevice, table: LearningTable, id: String): Row = assertNotNull(d.tables().get(table, listOf(JsonString(id))), "${d.name}: no ${table.sqlName} $id")

    /** Syncs every device until all settled, then runs the full oracle ([Sim.check]). */
    fun settleAndCheck(label: String) {
        repeat(3) { for (d in devices) sim.sync(d, Choice.REMINT, final = true) }
        assertEquals(emptyList(), sim.check(), label)
    }

    /** Replay-set ids of [card] on [d] (9.1 order). */
    fun replaySet(d: SimDevice, card: String): List<String> =
        kotobaya.sync.client.apply.DeviceCardCache().replaySet(d.tables(), card).map { it.id }

    override fun close() = server.close()
}
