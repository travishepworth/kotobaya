package kotobaya.android.review.actions

import kotobaya.android.review.CardRows
import kotobaya.android.review.QueueSource
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewSession
import kotobaya.android.review.ReviewState
import kotobaya.contracts.Reserved
import kotobaya.contracts.queue.QueueItem
import kotobaya.contracts.queue.QueueItemKind
import kotobaya.contracts.queue.ReviewQueue
import kotobaya.contracts.sync.JsonBool
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.DefaultCanonicalJson
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.apply.Row
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.toOp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewActionsTest {
    private val device = "01931f3e-0000-7000-8000-0000000000a1"
    private var now = 1_760_000_000_000L
    private val tomorrow = 1_760_086_400_000L
    private val ids = UuidV7Source(Random(11))
    private val applier = DeviceOpApplier<InMemoryLearningTables>()
    private val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val minter = OpMinter(store, applier, Clock { now }, ids)
    private val actions = ReviewActions(store, minter, { now }, { tomorrow })

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, device) }
    }

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    private fun seed(): Pair<String, String> {
        val noteId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "entry_seq" to JsonNumber(1358280.0),
                    "fields" to obj(
                        "expression" to JsonString("食べる"),
                        "reading" to JsonString("食[た]べる"),
                        "meaning" to JsonString("to eat"),
                    ),
                ),
            ),
        )
        val cardId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.CARD_CREATED,
                obj("id" to JsonString(cardId), "note_id" to JsonString(noteId), "kind" to JsonString("meaning")),
            ),
        )
        return noteId to cardId
    }

    private fun ops(type: String): List<PendingOpRow> = store.rows().filter { it.opType == type }

    private fun payload(row: PendingOpRow): Map<String, JsonValue> =
        (DefaultCanonicalJson.decode(row.payload).value as JsonObject).members

    private fun card(id: String): Row = checkNotNull(store.learning().get(LearningTable.CARD, listOf(JsonString(id))))

    /** The queue: live, non-suspended, non-buried cards; never-forget / blacklisted entries leave it. */
    private val queue = QueueSource {
        val t = store.learning()
        val items = t.all(LearningTable.CARD).filter { c ->
            val note = t.get(LearningTable.NOTE, listOf(c["note_id"]!!))
            val entry = (note?.get("entry_seq") as? JsonNumber)?.value
            val override = entry?.let { t.get(LearningTable.WORD_STATUS, listOf(JsonNumber(it))) }?.get("override_status")
            CardRows.live(c) &&
                (c["suspended"] as? JsonBool)?.value != true &&
                ((c["buried_until"] as? JsonNumber)?.value ?: 0.0) <= now &&
                override != JsonString("never_forget") && override != JsonString("blacklisted")
        }.map { QueueItem(CardRows.str(it["id"])!!, QueueItemKind.NEW, null) }
        ReviewQueue(items, emptyList(), null)
    }

    private fun session() = ReviewSession(store, minter, queue, { now }, { 0 }, { ids.newOpId(it) }, { ReviewPrefs() })

    @Test
    fun suspendMintsOneOpLeavesTheQueueAndUnsuspendRestoresIt() {
        val (_, cardId) = seed()
        val s = session().also { it.start() }
        assertIs<ReviewState.Showing>(s.state.value)
        assertIs<ActionResult.Done>(actions.suspend(cardId))
        s.start()
        assertIs<ReviewState.Empty>(s.state.value)
        assertEquals(1, ops("card.suspended").size)
        assertEquals(JsonString(cardId), payload(ops("card.suspended").single())["id"])
        assertEquals(JsonBool(true), card(cardId)["suspended"])
        assertIs<ActionResult.Done>(actions.suspend(cardId))
        assertEquals(1, ops("card.unsuspended").size)
        s.start()
        assertIs<ReviewState.Showing>(s.state.value)
    }

    @Test
    fun buryHidesUntilNextStudyDayAndReturnsAfter() {
        val (_, cardId) = seed()
        assertIs<ActionResult.Done>(actions.bury(cardId))
        assertEquals(JsonNumber(tomorrow.toDouble()), payload(ops("card.buried").single())["until"])
        val s = session().also { it.start() }
        assertIs<ReviewState.Empty>(s.state.value)
        now = tomorrow
        s.start()
        assertIs<ReviewState.Showing>(s.state.value)
    }

    @Test
    fun flagMintsFlagSetAndUndoRestoresThePreviousColour() {
        val (_, cardId) = seed()
        assertIs<ActionResult.Done>(actions.flag(cardId, 3))
        assertEquals(JsonNumber(3.0), card(cardId)["flags"])
        assertTrue(actions.undo())
        assertEquals(JsonNumber(0.0), card(cardId)["flags"])
        assertEquals(listOf(3.0, 0.0), ops("card.flag_set").map { (payload(it)["flags"] as JsonNumber).value })
    }

    @Test
    fun neverForgetSetsOverrideRemovesTheCardFromTheQueueAndClearingRestoresIt() {
        val (_, cardId) = seed()
        assertIs<ActionResult.Done>(actions.neverForget(cardId))
        val set = payload(ops("word.status_set").single())
        assertEquals(JsonString("never_forget"), set["status"])
        assertEquals(JsonNumber(1358280.0), set["entry_seq"])
        val s = session().also { it.start() }
        assertIs<ReviewState.Empty>(s.state.value)
        assertIs<ActionResult.Done>(actions.neverForget(cardId))
        assertEquals(1, ops("word.status_cleared").size)
        s.start()
        assertIs<ReviewState.Showing>(s.state.value)
    }

    @Test
    fun blacklistAndUndoReturnToThePriorOverride() {
        val (_, cardId) = seed()
        assertIs<ActionResult.Done>(actions.neverForget(cardId))
        assertIs<ActionResult.Done>(actions.blacklist(cardId))
        assertEquals("blacklisted", actions.stateOf(cardId)?.wordOverride)
        assertTrue(actions.undo())
        assertEquals("never_forget", actions.stateOf(cardId)?.wordOverride)
    }

    @Test
    fun editRoundTripRerendersOutboxHasTheOpAndAPulledCopyMatches() {
        val (noteId, cardId) = seed()
        val s = session().also { it.start() }
        val before = assertIs<ReviewState.Showing>(s.state.value).card.back.html
        val r = actions.saveEdit(cardId, mapOf("meaning" to "to consume"))
        assertIs<ActionResult.Done>(r)
        s.refreshCurrent()
        val after = assertIs<ReviewState.Showing>(s.state.value).card.back.html
        assertTrue(after.contains("to consume") && !before.contains("to consume"))
        val upd = ops("note.updated").single()
        assertEquals(JsonString("to consume"), ((payload(upd)["set"] as JsonObject).members["fields"] as JsonObject).members["meaning"])
        // a second replica that pulls every op converges to the same note
        val other = InMemoryLearningTables.seeded()
        store.rows().forEachIndexed { i, row -> applier.applyTo(other, row.toOp().copy(seq = i + 1L)) }
        val mine = store.learning().get(LearningTable.NOTE, listOf(JsonString(noteId)))
        val theirs = other.get(LearningTable.NOTE, listOf(JsonString(noteId)))
        assertNotNull(theirs)
        assertEquals(CardRows.fieldsOf(checkNotNull(mine)), CardRows.fieldsOf(theirs))
        assertTrue(actions.undo())
        assertEquals("to eat", actions.editable(cardId)?.values?.get("meaning"))
    }

    @Test
    fun emptyingTheFrontFieldWarns() {
        val (_, cardId) = seed()
        val r = assertIs<ActionResult.Done>(actions.saveEdit(cardId, mapOf("expression" to "", "reading" to "", "meaning" to "")))
        assertTrue(r.warnings.isNotEmpty())
    }
}
