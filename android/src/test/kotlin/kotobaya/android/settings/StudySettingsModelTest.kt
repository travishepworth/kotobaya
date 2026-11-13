package kotobaya.android.settings

import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.apply.DeviceOpApplier
import kotobaya.sync.client.apply.InMemoryLearningTables
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.InMemoryDeviceStore
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import kotobaya.sync.client.store.UuidV7Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudySettingsModelTest {
    private val store = InMemoryDeviceStore(InMemoryLearningTables.seeded()) { it.copy() }
    private val minter = OpMinter(store, DeviceOpApplier<InMemoryLearningTables>(), Clock { 1_760_000_000_000L }, UuidV7Source(Random(3)))
    private val minted = mutableListOf<JsonObject>()
    private val model = StudySettingsModel(
        readRaw = { emptyMap() },
        mint = { type, payload ->
            assertEquals(OpType.SETTINGS_SET, type)
            minter.mint(type, payload).also { if (it is MintResult.Minted) minted += payload }
        },
    )

    init {
        store.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, "01931f3e-0000-7000-8000-0000000000a1") }
    }

    private fun pending() = store.transaction { it.outbox.inState(PendingState.PENDING).size }

    @Test
    fun newLimitAndBoundaryEachMintOneSettingsOp() {
        assertEquals(StudyResult.Saved, model.set(StudyKey.NEW_PER_DAY, 35))
        assertEquals(1, pending())
        assertEquals(StudyResult.Saved, model.set(StudyKey.ROLLOVER_HOUR, 6))
        assertEquals(2, pending())
        assertEquals(JsonString("scheduler.new_per_day"), minted[0].members["key"])
        assertEquals(JsonNumber(35.0), minted[0].members["value"])
        assertEquals(JsonString("scheduler.day_rollover_hour"), minted[1].members["key"])
        assertEquals(JsonNumber(6.0), minted[1].members["value"])
    }

    @Test
    fun outOfRangeMintsNothing() {
        assertEquals(StudyResult.OutOfRange, model.set(StudyKey.ROLLOVER_HOUR, 24))
        assertEquals(StudyResult.OutOfRange, model.set(StudyKey.ROLLOVER_HOUR, -1))
        assertEquals(StudyResult.OutOfRange, model.set(StudyKey.NEW_PER_DAY, 2147483648L))
        assertEquals(StudyResult.OutOfRange, model.set(StudyKey.NEW_PER_DAY, -5))
        assertEquals(0, pending())
    }

    @Test
    fun unpairedDeviceIsRefused() {
        store.transaction { it.syncState.remove(SyncStateKeys.DEVICE_ID) }
        assertIs<StudyResult.Refused>(model.set(StudyKey.NEW_PER_DAY, 35))
    }
}
