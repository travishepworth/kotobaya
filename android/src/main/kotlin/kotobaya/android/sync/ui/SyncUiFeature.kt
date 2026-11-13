package kotobaya.android.sync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncStatusRepository
import kotobaya.android.sync.SyncTriggers
import kotobaya.android.sync.SyncTrigger
import kotobaya.contracts.Uuid
import kotobaya.sync.client.apply.DeviceDiagnostics
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.rejected.RejectedActionResult
import kotobaya.sync.client.rejected.RejectedEntry
import kotobaya.sync.client.rejected.RejectedOpsService
import kotobaya.sync.client.remint.RemintOutcome
import kotobaya.sync.client.remint.RemintPreview
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source
import org.koin.compose.koinInject
import org.koin.dsl.module

private enum class Page { SYNC, REJECTED, REMINT }

/** Everything the flow touches, so the composable can be given fakes. */
class SyncUiActions(
    val status: SyncStatusRepository,
    val triggers: SyncTriggers,
    val store: SqlDelightDeviceStore,
    val rejected: RejectedOpsService<SqlLearningTables>,
)

/** The Sync screen, the rejected-ops screen and the Re-mint screen as one flow with its own back stack. */
@Composable
fun SyncFlow(actions: SyncUiActions, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view by actions.status.status.collectAsState()
    var page by remember { mutableStateOf(Page.SYNC) }
    var entries by remember { mutableStateOf<List<RejectedEntry>>(emptyList()) }
    var refused by remember { mutableStateOf<List<Uuid>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<RemintPreview?>(null) }
    var listShown by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val loaded: Pair<List<RejectedEntry>, List<Uuid>> = withContext(Dispatchers.IO) {
                actions.status.refresh()
                val rows = actions.rejected.list()
                val ids: List<Uuid> = actions.store.read { tx -> DeviceDiagnostics.refusedCacheCards(tx.learning) }
                rows to ids
            }
            val (list, ids) = loaded
            entries = list
            refused = ids
        }
    }

    fun openRemint(note: String? = null) {
        scope.launch {
            preview = withContext(Dispatchers.IO) { actions.rejected.previewRemint() }
            listShown = false
            message = note
            page = Page.REMINT
        }
    }

    LaunchedEffect(view) { reload() }
    BackHandler {
        when (page) {
            Page.SYNC -> onClose()
            Page.REJECTED -> page = Page.SYNC
            Page.REMINT -> page = Page.REJECTED
        }
    }

    val done = T(R.string.rejected_done)
    val stale = T(R.string.remint_stale)
    val remintDone = T(R.string.remint_done)
    fun report(r: RejectedActionResult) {
        message = when (r) {
            is RejectedActionResult.Done -> done
            is RejectedActionResult.Unavailable -> context.getString(R.string.rejected_unavailable, r.reason)
        }
        actions.triggers.background(SyncTrigger.MANUAL)
        reload()
    }

    when (page) {
        Page.SYNC -> SyncScreenContent(
            view = view,
            refusedCardIds = refused,
            onSyncNow = { scope.launch { actions.triggers.syncNow() } },
            onOpenRejected = { message = null; page = Page.REJECTED },
            onCopy = { text ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("kotobaya-sync", text))
            },
        )
        Page.REJECTED -> RejectedOpsContent(
            entries = entries,
            message = message,
            onRetry = { e -> scope.launch { report(withContext(Dispatchers.IO) { actions.rejected.retry(listOf(e.row.opId)) }) } },
            onDiscard = { e -> scope.launch { report(withContext(Dispatchers.IO) { actions.rejected.discard(listOf(e.row.opId)) }) } },
            onDiscardAll = { scope.launch { report(withContext(Dispatchers.IO) { actions.rejected.discardAll() }) } },
            remintAvailable = entries.any { !it.retryable },
            onOpenRemint = ::openRemint,
        )
        Page.REMINT -> {
            val p = preview
            if (p != null) {
                val labels = entries.associate { it.row.opId to "${it.row.opType} ${it.row.entityId}" }
                RemintContent(
                    view = RemintView.of(p),
                    parentLabels = labels,
                    // never offered while the clock is wrong: core's preview does not check clock_blocked
                    clockBlocked = view?.status?.phase is SyncPhase.ClockWrong,
                    listShown = listShown,
                    message = message,
                    onShowList = { listShown = true },
                    onConfirm = {
                        scope.launch {
                            when (val r = withContext(Dispatchers.IO) { actions.rejected.remint(p) }) {
                                is RemintOutcome.Reminted -> {
                                    message = remintDone
                                    page = Page.REJECTED
                                    actions.triggers.background(SyncTrigger.MANUAL)
                                    reload()
                                }
                                is RemintOutcome.StalePreview -> {
                                    openRemint(stale)
                                }
                                is RemintOutcome.Unavailable -> {
                                    openRemint(context.getString(clockBlockRes(r.reason)))
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Home chip + full-screen flow it opens. */
@Composable
fun SyncChipWithFlow() {
    val actions = koinInject<SyncUiActions>()
    val view by actions.status.status.collectAsState()
    var open by remember { mutableStateOf(false) }
    SyncChip(view) { open = true }
    if (open) SyncFlowDialog(actions) { open = false }
}

@Composable
private fun SyncFlowDialog(actions: SyncUiActions, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { SyncFlow(actions, onClose) }
    }
}

@Composable
private fun SyncSettingsSection() {
    val actions = koinInject<SyncUiActions>()
    var open by remember { mutableStateOf(false) }
    Button(onClick = { open = true }) { Text(T(R.string.sync_title)) }
    if (open) SyncFlowDialog(actions) { open = false }
}

val syncUiModule = module {
    single {
        val client = get<kotobaya.android.sync.SyncClient>()
        val rejected = RejectedOpsService(
            client.store,
            get<OpMinter<SqlLearningTables>>(),
            Clock { System.currentTimeMillis() },
            UuidV7Source(SecureRandom().asKotlinRandom()),
        )
        SyncUiActions(get(), get(), client.store, rejected)
    }
}

/** THE registration for featureEntries (home chip, a Settings entry). */
val syncUiFeature = FeatureEntry(
    id = "sync-ui",
    module = syncUiModule,
    settingsSections = listOf(SettingsSection("sync", R.string.sync_title) { SyncSettingsSection() }),
    homeStatusChip = { SyncChipWithFlow() },
)
