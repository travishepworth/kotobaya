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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.sync.SyncFlowIntents
import kotobaya.android.sync.SyncFlowUiState
import kotobaya.android.sync.SyncPage
import kotobaya.sync.client.protocol.SyncPhase

/** The Sync screen, the rejected-ops screen and the Re-mint screen as one flow with its own back stack. */
@Composable
fun SyncFlow(state: SyncFlowUiState, intents: SyncFlowIntents) {
    val context = LocalContext.current
    BackHandler(onBack = intents::back)
    val message = state.message?.let { T(it) }
    when (state.page) {
        SyncPage.SYNC -> SyncScreenContent(
            view = state.view,
            refusedCardIds = state.refusedCardIds,
            onSyncNow = intents::syncNow,
            onOpenRejected = intents::openRejected,
            onCopy = { text ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("kotobaya-sync", text))
            },
        )
        SyncPage.REJECTED -> RejectedOpsContent(
            entries = state.entries,
            message = message,
            onRetry = intents::retry,
            onDiscard = intents::discard,
            onDiscardAll = intents::discardAll,
            remintAvailable = state.entries.any { !it.retryable },
            onOpenRemint = intents::openRemint,
        )
        SyncPage.REMINT -> {
            val p = state.preview
            if (p != null) {
                val labels = state.entries.associate { it.row.opId to "${it.row.opType} ${it.row.entityId}" }
                RemintContent(
                    view = RemintView.of(p),
                    parentLabels = labels,
                    // never offered while the clock is wrong: core's preview does not check clock_blocked
                    clockBlocked = state.view?.status?.phase is SyncPhase.ClockWrong,
                    listShown = state.listShown,
                    message = message,
                    onShowList = intents::showDiscardList,
                    onConfirm = intents::confirmRemint,
                )
            }
        }
    }
}

/** The flow as a full-screen dialog while [SyncFlowUiState.open]. */
@Composable
fun SyncFlowDialog(state: SyncFlowUiState, intents: SyncFlowIntents) {
    if (!state.open) return
    Dialog(onDismissRequest = intents::closeFlow, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { SyncFlow(state, intents) }
    }
}

/** Home chip + the full-screen flow it opens. */
@Composable
fun SyncChipWithFlow(state: SyncFlowUiState, intents: SyncFlowIntents) {
    SyncChip(state.view, onClick = intents::openFlow)
    SyncFlowDialog(state, intents)
}

/** Settings > Sync: a button that opens the flow. */
@Composable
fun SyncSettingsSection(state: SyncFlowUiState, intents: SyncFlowIntents) {
    Button(onClick = intents::openFlow) { Text(T(R.string.sync_title)) }
    SyncFlowDialog(state, intents)
}
