package kotobaya.android.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.sync.SyncStatusView
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbEmptyTone
import kotobaya.android.ui.components.KbFactRow
import kotobaya.android.ui.components.KbIconTile
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSectionTitle
import kotobaya.android.ui.components.KbTone
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme
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
    m.progress != null -> T(m.textRes, m.progress.first, m.progress.second)
    m.count != null -> T(m.textRes, m.count)
    m.timeMs != null -> T(m.textRes, formatTime(m.timeMs))
    m.httpStatus != null -> T(m.textRes, m.httpStatus)
    else -> T(m.textRes)
}

/**
 * The sync status row (Today's Sync panel): an icon tile in a calm tone, or the danger tone when the user should act;
 * tap opens the Sync screen.
 */
@Composable
fun SyncChip(view: SyncStatusView?, onClick: () -> Unit) {
    if (view == null) return
    val c = KbTheme.colors
    val m = chipFor(view)
    val good = m.kind == ChipKind.SYNCED || m.kind == ChipKind.SYNCING
    KbListRow(
        title = chipText(m),
        titleStyle = KbTheme.type.body,
        supporting = if (m.needsAction) T(R.string.sync_chip_open) else null,
        leading = {
            KbIconTile(
                if (m.kind == ChipKind.OFFLINE || m.kind == ChipKind.UNREACHABLE) KbIcons.Offline else if (m.needsAction) KbIcons.Alert else KbIcons.Sync,
                container = when {
                    m.needsAction -> c.dangerContainer
                    good -> c.successContainer
                    else -> c.surfaceSunken
                },
                tint = when {
                    m.needsAction -> c.onDangerContainer
                    good -> c.onSuccessContainer
                    else -> c.textMuted
                },
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(SyncTags.CHIP),
    )
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

private val mono = FontFamily.Monospace

/** Sync screen body. State in, events out. */
@Composable
fun SyncScreenContent(
    view: SyncStatusView?,
    refusedCardIds: List<Uuid>,
    onSyncNow: () -> Unit,
    onOpenRejected: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val c = KbTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(T(R.string.sync_title), style = KbTheme.type.headline, color = c.text)
        if (view == null) return@Column
        val s = view.status
        KbPanel(padding = PaddingValues(0.dp)) {
            SyncChip(view, onClick = {})
            KbDivider(inset = 16.dp)
            KbFactRow(T(R.string.sync_last_sync_label), s.lastSyncAt?.let(::formatTime) ?: T(R.string.sync_never))
            KbDivider(inset = 16.dp)
            KbFactRow(T(R.string.sync_waiting_label), s.pendingCount.toString())
            KbDivider(inset = 16.dp)
            KbFactRow(T(R.string.sync_attention_label), s.rejectedCount.toString())
            KbButton(
                T(R.string.sync_now), onSyncNow, Modifier.padding(16.dp).fillMaxWidth(),
                enabled = view.paired && s.phase !is SyncPhase.Syncing, loading = s.phase is SyncPhase.Syncing, leading = KbIcons.Sync,
            )
        }
        if (s.clockWarning) KbBanner(T(R.string.sync_clock_warning), kind = KbFeedback.Warning)
        s.lastError?.let { KbBanner(T(R.string.sync_last_error, it), kind = KbFeedback.Danger) }
        if (s.rejectedCount > 0) {
            KbButton(T(R.string.sync_open_rejected, s.rejectedCount), onOpenRejected, Modifier.fillMaxWidth(), variant = KbButtonVariant.Tonal)
        }

        KbSectionTitle(T(R.string.sync_diag_title), Modifier.padding(top = 8.dp))
        val lines = diagnosticsLines(view, refusedCardIds)
        val ur = view.upgradeRequired
        KbPanel(tone = KbTone.Sunken) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ur != null) {
                    Text(T(R.string.sync_diag_upgrade, ur.seq?.toString() ?: "?", ur.opId ?: "?"), style = KbTheme.type.bodySm, color = c.text)
                    Text(T(R.string.sync_diag_upgrade_error, ur.error), style = KbTheme.type.bodySm, color = c.text)
                }
                if (s.refusedCacheCards > 0) {
                    Text(T(R.string.sync_diag_refused_cards, s.refusedCacheCards), style = KbTheme.type.bodySm, color = c.text)
                    refusedCardIds.forEach { Text(it, style = KbTheme.type.caption.copy(fontFamily = mono), color = c.textMuted) }
                }
                if (ur == null && s.refusedCacheCards == 0) Text(T(R.string.sync_diag_none), style = KbTheme.type.bodySm, color = c.textMuted)
            }
        }
        KbButton(T(R.string.sync_diag_copy), { onCopy(lines.joinToString("\n")) }, variant = KbButtonVariant.Outline)
    }
}

/** Plain-English "why" of a rejected row. */
@Composable
private fun reasonText(code: String?): String {
    val res = rejectionReasonRes(code)
    return if (res == R.string.rejected_code_generic) T(res, code.orEmpty()) else T(res)
}

/** One rejected op: what, when, why, and its actions. Children are indented under their parent. */
@Composable
fun RejectedRow(e: RejectedEntry, indented: Boolean, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val c = KbTheme.colors
    KbPanel(Modifier.fillMaxWidth().padding(start = if (indented) 24.dp else 0.dp), padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (indented) KbOverline(T(R.string.rejected_child))
            Text(T(R.string.rejected_what, e.row.opType, e.row.entityId), style = KbTheme.type.titleSm.copy(fontFamily = mono, fontSize = KbTheme.type.bodySm.fontSize), color = c.text)
            Text(T(R.string.rejected_when, formatTime(e.row.createdAt)), style = KbTheme.type.caption, color = c.textMuted)
            Text(T(R.string.rejected_why, reasonText(e.row.rejectionCode)), style = KbTheme.type.bodySm, color = c.text)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (e.retryable) KbButton(T(R.string.rejected_retry), onRetry, variant = KbButtonVariant.Tonal)
                KbButton(T(R.string.rejected_discard), onDiscard, variant = KbButtonVariant.Outline)
            }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(T(R.string.rejected_title), style = KbTheme.type.headline, color = KbTheme.colors.text)
        message?.let { KbBanner(it) }
        if (entries.isEmpty()) {
            KbEmptyState(T(R.string.rejected_empty), tone = KbEmptyTone.Clear, page = true)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (remintAvailable) KbButton(T(R.string.rejected_remint_open), onOpenRemint, Modifier.weight(1f, fill = false))
            KbButton(T(R.string.rejected_discard_all), onDiscardAll, variant = KbButtonVariant.Outline)
        }
        LazyColumn(Modifier.testTag(SyncTags.REJECTED_LIST), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
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
    val c = KbTheme.colors
    val gate = remintGate(view, clockBlocked, listShown)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(T(R.string.remint_title), style = KbTheme.type.headline, color = c.text)
        message?.let { KbBanner(it) }
        if (gate is RemintGate.Hidden) {
            KbBanner(T(gate.reasonRes), kind = KbFeedback.Warning)
            return@Column
        }
        KbPanel {
            Text(T(R.string.remint_summary, view.remintedOps, view.remintedReviews), style = KbTheme.type.body, color = c.text)
            Text(
                if (view.count == 0) T(R.string.remint_discard_none) else T(R.string.remint_discard_count, view.count),
                style = KbTheme.type.titleSm,
                color = if (view.count == 0) c.text else c.danger,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (view.count > 0) {
            if (!listShown) {
                KbButton(T(R.string.remint_show_list, view.count), onShowList, Modifier.testTag(SyncTags.REMINT_SHOW_LIST), variant = KbButtonVariant.Outline)
            } else {
                DiscardList(view, parentLabels, Modifier.weight(1f, fill = false))
            }
        }
        val enabled = (gate as RemintGate.Offered).confirmEnabled
        if (!enabled) Text(T(R.string.remint_confirm_hint), style = KbTheme.type.bodySm, color = c.textMuted)
        KbButton(T(R.string.remint_confirm), onConfirm, Modifier.fillMaxWidth().testTag(SyncTags.REMINT_CONFIRM), enabled = enabled)
    }
}

@Composable
private fun DiscardList(view: RemintView, parentLabels: Map<Uuid, String>, modifier: Modifier) {
    val rows = discardRows(view)
    LazyColumn(modifier.testTag(SyncTags.REMINT_LIST), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows) { row ->
            when (row) {
                is ListRow.Header -> KbOverline(
                    row.parentOpId?.let { T(R.string.remint_group_under, parentLabels[it] ?: it) } ?: T(R.string.remint_group_other),
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                is ListRow.Item -> DiscardRow(row.value)
            }
        }
    }
}

@Composable
private fun DiscardRow(d: DiscardEntry) {
    val c = KbTheme.colors
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(T(R.string.rejected_what, d.row.opType, d.row.entityId), style = KbTheme.type.bodySm.copy(fontFamily = mono), color = c.text)
        Text(T(R.string.rejected_when, formatTime(d.row.createdAt)), style = KbTheme.type.caption, color = c.textMuted)
        Text(T(discardReasonRes(d.reason)), style = KbTheme.type.caption, color = c.textMuted)
        if (d.code != null) Text(T(R.string.rejected_why, reasonText(d.code)), style = KbTheme.type.caption, color = c.textMuted)
    }
}
