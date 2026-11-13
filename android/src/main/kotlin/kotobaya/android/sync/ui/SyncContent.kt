package kotobaya.android.sync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.sync.SyncStatusView
import kotobaya.contracts.Uuid
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.rejected.RejectedEntry
import kotobaya.sync.client.rejected.rejectionCode
import kotobaya.sync.client.remint.DiscardEntry

/** Test tags. */
object SyncTags {
    const val CHIP = "sync_chip"
    const val REMINT_CONFIRM = "remint_confirm"
    const val REMINT_LIST = "remint_list"
    const val REMINT_SHOW_LIST = "remint_show_list"
    const val REJECTED_LIST = "rejected_list"
}

private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

/** The text the chip shows. */
@Composable
fun chipText(m: ChipModel): String = when {
    m.count != null -> T(m.textRes, m.count)
    m.timeMs != null -> T(m.textRes, formatTime(m.timeMs))
    m.httpStatus != null -> T(m.textRes, m.httpStatus)
    else -> T(m.textRes)
}

/** The Home chip; tap opens the Sync screen. */
@Composable
fun SyncChip(view: SyncStatusView?, onClick: () -> Unit) {
    if (view == null) return
    val m = chipFor(view)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (m.needsAction) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(vertical = 8.dp).testTag(SyncTags.CHIP).clickable(onClick = onClick),
    ) {
        Text(chipText(m), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** The Sync screen's diagnostics as plain text (what the copy button puts on the clipboard). */
fun diagnosticsLines(view: SyncStatusView, refusedCardIds: List<Uuid>): List<String> = buildList {
    val s = view.status
    s.lastError?.let { add("last_error: $it") }
    add("pending=${s.pendingCount} rejected=${s.rejectedCount} acked=${s.ackedCount} failures=${view.consecutiveFailures}")
    val ur = view.upgradeRequired
    if (ur != null) {
        add("upgrade_required: seq=${ur.seq} op_id=${ur.opId} unsupported=${ur.unsupported}")
        add("first_error: ${ur.error}")
    }
    if (s.refusedCacheCards > 0 || refusedCardIds.isNotEmpty()) {
        add("refused_cache_cards=${s.refusedCacheCards}")
        refusedCardIds.forEach { add("refused_card: $it") }
    }
}

/** Sync screen body. State in, events out. */
@Composable
fun SyncScreenContent(
    view: SyncStatusView?,
    refusedCardIds: List<Uuid>,
    onSyncNow: () -> Unit,
    onOpenRejected: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(T(R.string.sync_title), style = MaterialTheme.typography.titleLarge)
        if (view == null) return@Column
        val s = view.status
        SyncChip(view, onClick = {})
        Button(onClick = onSyncNow, enabled = view.paired && s.phase !is SyncPhase.Syncing) { Text(T(R.string.sync_now)) }
        Text(if (s.lastSyncAt != null) T(R.string.sync_last_sync, formatTime(s.lastSyncAt!!)) else T(R.string.sync_last_sync_never))
        s.lastError?.let { Text(T(R.string.sync_last_error, it)) }
        if (s.clockWarning) Text(T(R.string.sync_clock_warning), color = MaterialTheme.colorScheme.error)
        Text(T(R.string.sync_counts, s.pendingCount, s.rejectedCount))
        if (s.rejectedCount > 0) OutlinedButton(onClick = onOpenRejected) { Text(T(R.string.sync_open_rejected, s.rejectedCount)) }

        Text(T(R.string.sync_diag_title), style = MaterialTheme.typography.titleMedium)
        val lines = diagnosticsLines(view, refusedCardIds)
        val ur = view.upgradeRequired
        if (ur != null) {
            Text(T(R.string.sync_diag_upgrade, ur.seq?.toString() ?: "?", ur.opId ?: "?"))
            Text(T(R.string.sync_diag_upgrade_error, ur.error))
        }
        if (s.refusedCacheCards > 0) {
            Text(T(R.string.sync_diag_refused_cards, s.refusedCacheCards))
            refusedCardIds.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (ur == null && s.refusedCacheCards == 0) Text(T(R.string.sync_diag_none))
        OutlinedButton(onClick = { onCopy(lines.joinToString("\n")) }) { Text(T(R.string.sync_diag_copy)) }
    }
}

/** Plain-English "why" of a rejected row. */
@Composable
private fun reasonText(code: String?): String {
    val res = rejectionReasonRes(code)
    return if (res == R.string.rejected_code_generic) T(res, code.orEmpty()) else T(res)
}

/** One rejected op: what, when, why, and its actions. */
@Composable
fun RejectedRow(e: RejectedEntry, indented: Boolean, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = if (indented) 24.dp else 0.dp, top = 8.dp, bottom = 8.dp)) {
        if (indented) Text(T(R.string.rejected_child), style = MaterialTheme.typography.labelSmall)
        Text(T(R.string.rejected_what, e.row.opType, e.row.entityId), style = MaterialTheme.typography.titleSmall)
        Text(T(R.string.rejected_when, formatTime(e.row.createdAt)))
        Text(T(R.string.rejected_why, reasonText(e.row.rejectionCode)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.retryable) OutlinedButton(onClick = onRetry) { Text(T(R.string.rejected_retry)) }
            OutlinedButton(onClick = onDiscard) { Text(T(R.string.rejected_discard)) }
        }
    }
}

/** Rejected ops screen body: the list grouped under parents, and the entry to Re-mint. */
@Composable
fun RejectedOpsContent(
    entries: List<RejectedEntry>,
    message: String?,
    onRetry: (RejectedEntry) -> Unit,
    onDiscard: (RejectedEntry) -> Unit,
    onDiscardAll: () -> Unit,
    remintAvailable: Boolean,
    onOpenRemint: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.rejected_title), style = MaterialTheme.typography.titleLarge)
        message?.let { Text(it) }
        if (entries.isEmpty()) {
            Text(T(R.string.rejected_empty))
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (remintAvailable) Button(onClick = onOpenRemint) { Text(T(R.string.rejected_remint_open)) }
            OutlinedButton(onClick = onDiscardAll) { Text(T(R.string.rejected_discard_all)) }
        }
        LazyColumn(Modifier.testTag(SyncTags.REJECTED_LIST)) {
            items(groupRejected(entries), key = { it.first.row.opId }) { (e, indented) ->
                RejectedRow(e, indented, { onRetry(e) }, { onDiscard(e) })
            }
        }
    }
}

/**
 * Re-mint screen body (SYNC.md 10.4). The confirm button does not exist when [gate] is hidden (the reason is
 * shown instead) and is disabled until the discard list has been opened ([listShown]).
 */
@Composable
fun RemintContent(
    view: RemintView,
    parentLabels: Map<Uuid, String>,
    clockBlocked: Boolean,
    listShown: Boolean,
    message: String?,
    onShowList: () -> Unit,
    onConfirm: () -> Unit,
) {
    val gate = remintGate(view, clockBlocked, listShown)
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.remint_title), style = MaterialTheme.typography.titleLarge)
        message?.let { Text(it) }
        if (gate is RemintGate.Hidden) {
            Text(T(gate.reasonRes))
            return@Column
        }
        Text(T(R.string.remint_summary, view.remintedOps, view.remintedReviews))
        Text(if (view.count == 0) T(R.string.remint_discard_none) else T(R.string.remint_discard_count, view.count))
        if (view.count > 0) {
            if (!listShown) {
                OutlinedButton(onClick = onShowList, modifier = Modifier.testTag(SyncTags.REMINT_SHOW_LIST)) {
                    Text(T(R.string.remint_show_list, view.count))
                }
            } else {
                DiscardList(view, parentLabels, Modifier.weight(1f, fill = false))
            }
        }
        val enabled = (gate as RemintGate.Offered).confirmEnabled
        if (!enabled) Text(T(R.string.remint_confirm_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onConfirm, enabled = enabled, modifier = Modifier.testTag(SyncTags.REMINT_CONFIRM)) {
            Text(T(R.string.remint_confirm))
        }
    }
}

@Composable
private fun DiscardList(view: RemintView, parentLabels: Map<Uuid, String>, modifier: Modifier) {
    val rows = discardRows(view)
    LazyColumn(modifier.testTag(SyncTags.REMINT_LIST), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows) { row ->
            when (row) {
                is ListRow.Header -> Text(
                    row.parentOpId?.let { T(R.string.remint_group_under, parentLabels[it] ?: it) } ?: T(R.string.remint_group_other),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is ListRow.Item -> DiscardRow(row.value)
            }
        }
    }
}

@Composable
private fun DiscardRow(d: DiscardEntry) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Text(T(R.string.rejected_what, d.row.opType, d.row.entityId))
        Text(T(R.string.rejected_when, formatTime(d.row.createdAt)), style = MaterialTheme.typography.bodySmall)
        Text(T(discardReasonRes(d.reason)), style = MaterialTheme.typography.bodySmall)
        if (d.code != null) Text(T(R.string.rejected_why, reasonText(d.code)), style = MaterialTheme.typography.bodySmall)
    }
}
