package kotobaya.android.shell.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.OpApplier
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source

/** SHELL-A2 acceptance: LocalWrites is all-or-nothing and triggers sync once per committed write. */
class LocalWritesTest {
    private val device = "01931f3e-0000-7000-8000-0000000000d4"
    private var now = 1_733_584_666_000L // 2024-12-07
    private val clock = Clock { now }

    /** Learning data = the op ids applied, in order. */
    private val store = InMemoryDeviceStore(mutableListOf<String>()) { it.toMutableList() }
    private val applier = OpApplier<MutableList<String>> { tx, op, _ -> tx.learning.add(op.opId) }
    private val minter = OpMinter(store, applier, clock, UuidV7Source(kotlin.random.Random(7)))
    private var triggers = 0
    private val writes = LocalWrites(store, minter) { triggers++ }

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, device) }
    }

    private fun setting(key: String, value: Int) = MintRequest(
        OpType.SETTINGS_SET,
        JsonObject(mapOf("key" to JsonString(key), "value" to JsonNumber(value.toDouble()))),
    )

    @Test
    fun aRefusedMintLeavesTheStoreUnchangedAndEnqueuesNothing() {
        now = 1_500_000_000_000L // 2017: guard G1
        val before = store.syncState()
        val e = assertFailsWith<ProblemException> {
            writes.write<String> { tx ->
                // even a write the block did itself is rolled back with the refused mint
                tx.syncState.put("client.test_marker", "x")
                WritePlan.Mint(listOf(setting("scheduler.new_per_day", 15))) { _, _ -> "answer" }
            }
        }
        assertEquals(422, e.problem.status)
        assertEquals(FieldError("/reviewed_at", "before_floor", e.problem.errors.single().detail), e.problem.errors.single())
        assertEquals(emptyList(), store.rows())
        assertEquals(emptyList(), store.learning())
        assertEquals(before, store.syncState())
        assertNull(store.syncState()["client.test_marker"])
        assertEquals(0, triggers)
    }

    @Test
    fun anInvalidOpIsRefusedAtTheMappedRequestPointer() {
        val e = assertFailsWith<ProblemException> {
            writes.write<String> {
                WritePlan.Mint(
                    listOf(setting("scheduler.new_per_day", 15), MintRequest(OpType.SETTINGS_SET, JsonObject(mapOf("key" to JsonString("")))))
                    , invalidPointer = { "/values" },
                ) { _, _ -> "answer" }
            }
        }
        assertEquals("/values", e.problem.errors.single().pointer)
        assertEquals(emptyList(), store.rows())
        assertEquals(emptyList(), store.learning())
        assertEquals(0, triggers)
    }

    @Test
    fun aCommittedWriteEnqueuesExactlyTheMintedOpsAndTriggersSyncOnce() {
        var minted: List<String> = emptyList()
        val answer = writes.write<String> { tx ->
            assertEquals(emptyList(), tx.learning)
            WritePlan.Mint(listOf(setting("scheduler.new_per_day", 15), setting("scheduler.new_per_session", 5))) { t, ops ->
                minted = ops.map { it.opId }
                // the answer is built after the local apply, in the same transaction
                "applied=${t.learning.size}"
            }
        }
        assertEquals("applied=2", answer)
        assertEquals(2, minted.size)
        assertEquals(minted, store.rows().map { it.opId })
        assertEquals(minted, store.learning())
        assertEquals(listOf(true, true), store.rows().map { it.hlc.endsWith(device) })
        assertEquals(1, triggers)
    }

    @Test
    fun anAnswerPlanMintsNothingAndDoesNotTriggerSync() {
        val answer = writes.write<String> { WritePlan.Answer("existing") }
        assertEquals("existing", answer)
        assertEquals(emptyList(), store.rows())
        assertEquals(0, triggers)
    }

    @Test
    fun aProblemThrownByTheBlockRollsBackAndDoesNotTrigger() {
        val e = assertFailsWith<ProblemException> {
            writes.write<String> { tx ->
                tx.syncState.put("client.test_marker", "x")
                throw ProblemException(Problems.conflict("card_not_reviewable"))
            }
        }
        assertEquals(409, e.problem.status)
        assertNull(store.syncState()["client.test_marker"])
        assertEquals(0, triggers)
    }

    @Test
    fun aFailingTriggerDoesNotFailTheCommittedWrite() {
        val failing = LocalWrites(store, minter) { error("WorkManager unavailable") }
        val answer = failing.write<String> { WritePlan.Mint(listOf(setting("scheduler.new_per_day", 15))) { _, _ -> "ok" } }
        assertEquals("ok", answer)
        assertEquals(1, store.rows().size)
    }
}
