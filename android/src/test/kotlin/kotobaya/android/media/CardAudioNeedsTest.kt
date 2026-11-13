package kotobaya.android.media

import kotobaya.contracts.Reserved
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.JsonValue
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.apply.LearningTable
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.CycleResult
import kotobaya.sync.client.protocol.FailureKind
import kotobaya.sync.client.protocol.SyncStep
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.android.sync.SyncOutcome
import kotobaya.android.sync.prefetchAfterSync
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CardAudioNeedsTest {
    private val now = 1_760_000_000_000L
    private val day = 86_400_000L
    private val ids = UuidV7Source(Random(5))
    private val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val minter = OpMinter(store, DeviceOpApplier<InMemoryLearningTables>(), Clock { now }, ids)

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, "01931f3e-0000-7000-8000-0000000000a1") }
    }

    private fun obj(vararg p: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*p))

    private fun hash(c: Char) = c.toString().repeat(64)

    /** A vocab note whose expression plays [h], with a meaning card; returns the card id. */
    private fun cardWithAudio(h: String): String {
        val noteId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(
                OpType.NOTE_CREATED,
                obj(
                    "id" to JsonString(noteId),
                    "note_type_id" to JsonString(Reserved.NOTE_TYPE_VOCAB),
                    "fields" to obj(
                        "expression" to JsonString("食べる[sound:$h]"),
                        "reading" to JsonString("たべる"),
                        "meaning" to JsonString("to eat"),
                    ),
                ),
            ),
        )
        val cardId = ids.newOpId(now)
        assertIs<MintResult.Minted>(
            minter.mint(OpType.CARD_CREATED, obj("id" to JsonString(cardId), "note_id" to JsonString(noteId), "kind" to JsonString("meaning"))),
        )
        return cardId
    }

    private fun dueAt(cardId: String, at: Long) = store.transaction { tx ->
        val row = checkNotNull(tx.learning.get(LearningTable.CARD, listOf(JsonString(cardId))))
        tx.learning.put(LearningTable.CARD, row + mapOf("state" to JsonString("review"), "due_at" to JsonNumber(at.toDouble())))
    }

    @Test
    fun returnsHashesForDueCardsInsideTheWindowOnly() {
        val soon = cardWithAudio(hash('a'))
        val later = cardWithAudio(hash('b'))
        val suspended = cardWithAudio(hash('c'))
        dueAt(soon, now + day)
        dueAt(later, now + 10 * day)
        assertIs<MintResult.Minted>(minter.mint(OpType.CARD_SUSPENDED, obj("id" to JsonString(suspended))))
        val needs = CardAudioNeeds(store).needs(now, 2)
        assertEquals(mapOf(hash('a') to now + day), needs.associate { it.hash to it.dueAtMs })
    }

    @Test
    fun successfulSyncEnqueuesPrefetchAndFailureDoesNot() {
        val calls = mutableListOf<Boolean>()
        val ok = SyncOutcome.Ran(CycleResult.Completed(pushedOps = 1, pulledOps = 2, purgedRows = 0))
        prefetchAfterSync(ok, { true }) { calls += it }
        assertEquals(listOf(true), calls)
        prefetchAfterSync(SyncOutcome.Ran(CycleResult.Failed(FailureKind.OFFLINE, SyncStep.PUSH, "x")), { true }) { calls += it }
        prefetchAfterSync(SyncOutcome.Offline, { true }) { calls += it }
        assertEquals(1, calls.size)
    }
}
