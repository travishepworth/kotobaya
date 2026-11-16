package kotobaya.android.shell.api.device

import android.app.Application
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotobaya.android.ankiapi.provider.AnkiContract
import kotobaya.android.ankiapi.provider.AnkiProvider
import kotobaya.android.ankiapi.provider.InMemoryAnkiStore
import kotobaya.android.ankiapi.provider.ProviderCallLog
import kotobaya.android.ankiapi.store.AddOutcome
import kotobaya.android.shell.events.EventingAnkiStore
import kotobaya.android.shell.events.NoteAdd
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.shell.web.BridgeMessage
import kotobaya.android.sync.SyncOutcome
import kotobaya.sync.client.protocol.CycleResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SHELL-A6 acceptance: which native events go out, and how often. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShellEventsTest {
    private val sent = ArrayList<BridgeMessage.Event>()
    private val status = FakeDeviceOps()
    private val events = ShellEvents(
        scope = CoroutineScope(Dispatchers.Unconfined),
        send = { synchronized(sent) { sent += it } },
        syncChanged = { 48213L to 1 },
        syncStatus = { status.syncStatus().toJson() },
        intervalMs = 0,
    )

    private fun types() = synchronized(sent) { sent.map { it.type } }

    @Test
    fun aPullThatAppliesOpsEmitsExactlyOneSyncChanged() {
        events.onSyncOutcome(SyncOutcome.Ran(CycleResult.Completed(pushedOps = 0, pulledOps = 5, purgedRows = 0, pullAdvanced = true)))
        assertEquals(1, types().count { it == "sync.changed" })
        val data = sent.first { it.type == "sync.changed" }.data
        assertEquals(setOf("high_water_seq", "pending_ops"), data.members.keys)
    }

    @Test
    fun aCycleThatAppliedNothingEmitsNoSyncChanged() {
        events.onSyncOutcome(SyncOutcome.Ran(CycleResult.Completed(pushedOps = 2, pulledOps = 0, purgedRows = 0)))
        events.onSyncOutcome(SyncOutcome.Offline)
        assertEquals(0, types().count { it == "sync.changed" })
    }

    @Test
    fun connectivityIsSentOnlyOnChange() {
        events.onConnectivity(true)
        events.onConnectivity(true)
        events.onConnectivity(false)
        assertEquals(2, types().count { it == "shell.connectivity" })
    }

    @Test
    fun anAnkiProviderInsertEmitsNoteCreated() {
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(AnkiContract.READ_WRITE_PERMISSION)
        val provider = Robolectric.setupContentProvider(AnkiProvider::class.java, AnkiContract.AUTHORITY)
        ProviderCallLog.clear()
        val memory = InMemoryAnkiStore()
        val adder = NoteAdd { n -> AddOutcome.Added(memory.addNote(n), "01931f3e-0000-7000-8000-0000000000aa") }
        val store = EventingAnkiStore(memory, adder, events) { "01931f3e-0000-7000-8000-0000000000d4" }
        provider.attachStore(store)
        val values = ContentValues().apply {
            put(AnkiContract.Note.MID, InMemoryAnkiStore.BASIC_MODEL_ID)
            put(AnkiContract.Note.FLDS, "食べる\u001fto eat")
        }
        app.contentResolver.insert(AnkiContract.NOTES_URI, values)
        val created = synchronized(sent) { sent.filter { it.type == "note.created" } }
        assertEquals(1, created.size)
        assertEquals("01931f3e-0000-7000-8000-0000000000aa", (created[0].data.members["note_id"] as kotobaya.contracts.sync.JsonString).value)
        assertEquals("01931f3e-0000-7000-8000-0000000000d4", (created[0].data.members["origin_device_id"] as kotobaya.contracts.sync.JsonString).value)
    }
}
