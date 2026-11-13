package kotobaya.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.contracts.sync.OpType
import kotobaya.sync.client.mint.MintRequest
import kotobaya.sync.client.mint.MintResult
import kotobaya.sync.client.store.PendingState
import kotobaya.sync.client.store.SyncStateKeys
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class FirstUseTransactionTest {
    private val t0 = 1_733_500_000_000L

    @Test
    fun aTransactionAsTheFirstUseOfAFreshDatabaseSucceedsAndPersists() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val d1 = DurableDriver.open(ctx)
        val store1 = SqlDelightDeviceStore(d1)
        // the very first operation on the fresh file is a transaction
        store1.transaction { it.syncState.put(SyncStateKeys.DEVICE_ID, "01931f3e-0000-7000-8000-0000000000a1") }
        val stack = Stack(store1, TestTransport(TestServer(t0)), TestClock(t0), 1)
        val req = MintRequest(
            OpType.SETTINGS_SET,
            JsonObject(mapOf("key" to JsonString("test.k"), "value" to JsonNumber(1.0))),
        )
        val minted = assertIs<MintResult.Minted>(stack.minter.mintAll(listOf(req)))
        assertEquals(1, minted.ops.size)
        assertEquals(2L, DurableDriver.synchronousLevel(d1))
        d1.close()

        val d2 = DurableDriver.open(ctx)
        val rows = SqlDelightDeviceStore(d2).read { tx -> tx.outbox.inState(PendingState.PENDING) }
        assertEquals(listOf(minted.ops[0].opId), rows.map { it.opId })
        d2.close()
    }
}
