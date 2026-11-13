package kotobaya.android.sync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.sync.SyncFlowIntents
import kotobaya.android.sync.SyncFlowUiState
import kotobaya.android.sync.SyncPage
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbTopBar
import kotobaya.android.ui.theme.KbTheme
import kotobaya.sync.client.protocol.SyncPhase

/** The Sync screen, the rejected-ops screen and the Re-mint screen as one flow with its own back stack. */
@Composable
fun SyncFlow(state: SyncFlowUiState, intents: SyncFlowIntents) {
    val context = LocalContext.current
    BackHandler(onBack = intents::back)
    val message = state.message?.let { T(it) }
    Column(Modifier.fillMaxSize().background(KbTheme.colors.bg)) {
        KbTopBar(
            "",
            onBack = intents::back,
            backLabel = T(if (state.page == SyncPage.SYNC) R.string.sync_close else R.string.settings_back),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
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
        }
    }
}

/** The flow as a full-screen dialog while [SyncFlowUiState.open]. */
@Composable
fun SyncFlowDialog(state: SyncFlowUiState, intents: SyncFlowIntents) {
    if (!state.open) return
    Dialog(onDismissRequest = intents::closeFlow, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KotobayaDialogSurface { SyncFlow(state, intents) }
    }
}

@Composable
private fun KotobayaDialogSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(KbTheme.colors.bg)) { content() }
}

/** Today's sync row + the full-screen flow it opens. */
@Composable
fun SyncChipWithFlow(state: SyncFlowUiState, intents: SyncFlowIntents) {
    SyncChip(state.view, onClick = intents::openFlow)
    SyncFlowDialog(state, intents)
}

/** Settings › Sync: the status row and a button that opens the flow. */
@Composable
fun SyncSettingsSection(state: SyncFlowUiState, intents: SyncFlowIntents) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        KbPanel(padding = PaddingValues(0.dp)) {
            SyncChip(state.view, onClick = intents::openFlow)
            if (state.view != null) KbDivider(inset = 16.dp)
            KbButton(T(R.string.sync_open), intents::openFlow, Modifier.padding(16.dp).fillMaxWidth(), leading = KbIcons.Sync)
        }
    }
    SyncFlowDialog(state, intents)
}
