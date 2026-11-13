package kotobaya.android.sync

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotobaya.android.R
import kotobaya.android.sync.ui.clockBlockRes
import kotobaya.android.vm.StateViewModel
import kotobaya.android.vm.UiText
import kotobaya.contracts.Uuid
import kotobaya.sync.client.apply.DeviceDiagnostics
import kotobaya.sync.client.rejected.RejectedActionResult
import kotobaya.sync.client.rejected.RejectedEntry
import kotobaya.sync.client.rejected.RejectedOpsService
import kotobaya.sync.client.remint.RemintOutcome
import kotobaya.sync.client.remint.RemintPreview

/** What the sync flow (status, rejected ops, re-mint) reads and does. Blocking calls run on IO. */
interface SyncFlowPort {
    val status: StateFlow<SyncStatusView?>

    /** Recomputes the status, then the rejected ops and the refused-cache card ids. */
    fun reload(): Pair<List<RejectedEntry>, List<Uuid>>

    suspend fun syncNow()

    /** Starts a background sync cycle (after a retry, discard or re-mint). */
    fun syncInBackground()

    fun retry(opId: Uuid): RejectedActionResult

    fun discard(opId: Uuid): RejectedActionResult

    fun discardAll(): RejectedActionResult

    fun previewRemint(): RemintPreview

    fun remint(preview: RemintPreview): RemintOutcome
}

/** Everything the flow touches in the app. */
class SyncUiActions(
    val statusRepository: SyncStatusRepository,
    val triggers: SyncTriggers,
    val store: SqlDelightDeviceStore,
    val rejected: RejectedOpsService<SqlLearningTables>,
) : SyncFlowPort {
    override val status: StateFlow<SyncStatusView?> get() = statusRepository.status

    override fun reload(): Pair<List<RejectedEntry>, List<Uuid>> {
        statusRepository.refresh()
        val rows = rejected.list()
        val ids: List<Uuid> = store.read { tx -> DeviceDiagnostics.refusedCacheCards(tx.learning) }
        return rows to ids
    }

    override suspend fun syncNow() {
        triggers.syncNow()
    }

    override fun syncInBackground() = triggers.background(SyncTrigger.MANUAL)

    override fun retry(opId: Uuid) = rejected.retry(listOf(opId))

    override fun discard(opId: Uuid) = rejected.discard(listOf(opId))

    override fun discardAll() = rejected.discardAll()

    override fun previewRemint() = rejected.previewRemint()

    override fun remint(preview: RemintPreview) = rejected.remint(preview)
}

/** The pages of the sync flow, which has its own back stack. */
enum class SyncPage { SYNC, REJECTED, REMINT }

data class SyncFlowUiState(
    /** The status (the Home chip and the Sync page); null until the first refresh. */
    val view: SyncStatusView? = null,
    /** The full-screen flow is open. */
    val open: Boolean = false,
    val page: SyncPage = SyncPage.SYNC,
    val entries: List<RejectedEntry> = emptyList(),
    val refusedCardIds: List<Uuid> = emptyList(),
    val message: UiText? = null,
    val preview: RemintPreview? = null,
    /** The re-mint page's discard list is expanded. */
    val listShown: Boolean = false,
)

/** What the sync chip and flow can ask for (no-ops by default, for previews). */
interface SyncFlowIntents {
    fun openFlow() {}
    fun closeFlow() {}
    /** System back inside the flow. */
    fun back() {}
    fun syncNow() {}
    fun openRejected() {}
    fun retry(entry: RejectedEntry) {}
    fun discard(entry: RejectedEntry) {}
    fun discardAll() {}
    fun openRemint() {}
    fun showDiscardList() {}
    fun confirmRemint() {}
}

/**
 * The Home sync chip and the flow it opens (also opened from Settings > Sync).
 *
 * Consistency: the status, the rejected list and the refused-cache ids are separate `read {}` calls on the WAL
 * reader connection (BUG-8), not one snapshot, so a sync chunk committing in between can make them disagree for a
 * moment. That is acceptable because the flow only displays them; every action (retry, discard, re-mint) re-reads
 * inside its own write transaction in core, and the next status change reloads the lists.
 */
class SyncFlowViewModel(
    private val port: SyncFlowPort,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<SyncFlowUiState>(SyncFlowUiState(view = port.status.value), io), SyncFlowIntents {
    init {
        viewModelScope.launch {
            port.status.collect { v ->
                update { it.copy(view = v) }
                if (current.open) reload()
            }
        }
    }

    private fun reload() {
        launchIo {
            val (list, ids) = port.reload()
            update { it.copy(entries = list, refusedCardIds = ids) }
        }
    }

    override fun openFlow() {
        update { it.copy(open = true, page = SyncPage.SYNC, message = null, preview = null, listShown = false) }
        reload()
    }

    override fun closeFlow() = update { it.copy(open = false) }

    override fun back() = when (current.page) {
        SyncPage.SYNC -> closeFlow()
        SyncPage.REJECTED -> update { it.copy(page = SyncPage.SYNC) }
        SyncPage.REMINT -> update { it.copy(page = SyncPage.REJECTED) }
    }

    override fun syncNow() {
        launchIo { port.syncNow() }
    }

    override fun openRejected() = update { it.copy(message = null, page = SyncPage.REJECTED) }

    private fun report(r: RejectedActionResult) {
        val message = when (r) {
            is RejectedActionResult.Done -> UiText(R.string.rejected_done)
            is RejectedActionResult.Unavailable -> UiText(R.string.rejected_unavailable, r.reason)
        }
        update { it.copy(message = message) }
        port.syncInBackground()
        reload()
    }

    override fun retry(entry: RejectedEntry) {
        launchIo { report(port.retry(entry.row.opId)) }
    }

    override fun discard(entry: RejectedEntry) {
        launchIo { report(port.discard(entry.row.opId)) }
    }

    override fun discardAll() {
        launchIo { report(port.discardAll()) }
    }

    private fun openRemint(note: UiText?) {
        launchIo {
            val preview = port.previewRemint()
            update { it.copy(preview = preview, listShown = false, message = note, page = SyncPage.REMINT) }
        }
    }

    override fun openRemint() = openRemint(null)

    override fun showDiscardList() = update { it.copy(listShown = true) }

    override fun confirmRemint() {
        val p = current.preview ?: return
        launchIo {
            when (val r = port.remint(p)) {
                is RemintOutcome.Reminted -> {
                    update { it.copy(message = UiText(R.string.remint_done), page = SyncPage.REJECTED) }
                    port.syncInBackground()
                    reload()
                }
                is RemintOutcome.StalePreview -> openRemint(UiText(R.string.remint_stale))
                is RemintOutcome.Unavailable -> openRemint(UiText(clockBlockRes(r.reason)))
            }
        }
    }
}
