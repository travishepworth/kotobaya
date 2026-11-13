package kotobaya.android.sync.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.R
import kotobaya.android.sync.SyncStatusView
import kotobaya.sync.client.protocol.SyncPhase
import kotobaya.sync.client.protocol.SyncStatus
import kotobaya.sync.client.rejected.RejectedEntry
import kotobaya.sync.client.remint.ClockActionBlock
import kotobaya.sync.client.remint.DiscardEntry
import kotobaya.sync.client.remint.DiscardReason
import kotobaya.sync.client.store.PendingOpRow
import kotobaya.sync.client.store.PendingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int, vararg a: Any) = context.getString(id, *a)

    private fun view(
        phase: SyncPhase = SyncPhase.Idle,
        pending: Int = 0,
        rejected: Int = 0,
        lastSyncAt: Long? = null,
        paired: Boolean = true,
        unreachable: Boolean = false,
    ) = SyncStatusView(
        status = SyncStatus(phase, pending, 0, rejected, lastSyncAt, null, false, false),
        paired = paired,
        staleOverTwoDays = false,
        upgradeRequired = null,
        consecutiveFailures = 0,
        unreachable = unreachable,
    )

    @Test
    fun everyStatusMapsToItsChipText() {
        val cases = listOf(
            view(paired = false) to str(R.string.sync_chip_not_paired),
            view(SyncPhase.Syncing) to str(R.string.sync_chip_syncing),
            view(SyncPhase.ClockWrong(null, 3_000_000L)) to str(R.string.sync_chip_clock),
            view(SyncPhase.UpgradeRequired(5, "o", true, "e")) to str(R.string.sync_chip_update_required),
            view(SyncPhase.AuthRequired) to str(R.string.sync_chip_auth),
            view(rejected = 3) to str(R.string.sync_chip_attention, 3),
            view(SyncPhase.Rebuilding) to str(R.string.sync_chip_rebuilding),
            view(SyncPhase.Offline) to str(R.string.sync_chip_offline),
            view(SyncPhase.ServerUnreachable(null), unreachable = true) to str(R.string.sync_chip_unreachable),
            view(SyncPhase.ServerUnreachable(503)) to str(R.string.sync_chip_server_error_code, 503),
            view(SyncPhase.ClientError(400)) to str(R.string.sync_chip_client_error),
            view(pending = 4) to str(R.string.sync_chip_pending, 4),
            view() to str(R.string.sync_chip_never),
        )
        val current = androidx.compose.runtime.mutableStateOf(cases.first().first)
        var shown = ""
        rule.setContent { shown = chipText(chipFor(current.value)) }
        for ((v, text) in cases) {
            current.value = v
            rule.waitForIdle()
            assertEquals(text, shown)
        }
        // synced shows the time
        assertEquals(ChipKind.SYNCED, chipFor(view(lastSyncAt = 1_700_000_000_000L)).kind)
        // rejected rows win over offline: the user has something to do
        assertEquals(ChipKind.NEEDS_ATTENTION, chipFor(view(SyncPhase.Offline, rejected = 1)).kind)
    }

    @Test
    fun chipRendersAndTapsThrough() {
        var taps = 0
        rule.setContent { SyncChip(view(rejected = 2)) { taps++ } }
        rule.onNodeWithText(str(R.string.sync_chip_attention, 2)).performClick()
        assertEquals(1, taps)
    }

    private fun row(id: String, type: String = "review.logged", error: String? = "clock_ahead: too far") = PendingOpRow(
        opId = id, opType = type, opVersion = 1, entityType = "card", entityId = "e-$id", hlc = "h$id", payload = "{}",
        createdAt = 1_700_000_000_000L, state = PendingState.REJECTED, lastError = error,
    )

    private fun entry(id: String, parent: String?, code: String = "unknown_reference", retryable: Boolean = true) =
        RejectedEntry(row(id, error = "$code: m"), code, parent, retryable)

    @Test
    fun rejectedChildrenAreGroupedUnderParent() {
        val a = entry("a", null)
        val b = entry("b", "a")
        val c = entry("c", null)
        val d = entry("d", "a")
        // input in hlc order a, b, c, d: d must move up under a
        val grouped = groupRejected(listOf(a, b, c, d))
        assertEquals(listOf("a" to false, "b" to true, "d" to true, "c" to false), grouped.map { it.first.row.opId to it.second })

        rule.setContent {
            RejectedOpsContent(listOf(a, b, c, d), null, {}, {}, {}, remintAvailable = false, onOpenRemint = {})
        }
        rule.onAllNodesWithText(str(R.string.rejected_why, str(R.string.rejected_code_unknown_reference)), useUnmergedTree = true)
            .onFirst().assertExists()
        rule.onAllNodesWithText(str(R.string.rejected_child)).onFirst().assertExists()
    }

    private fun discard(i: Int, parent: String?) = DiscardEntry(row("x$i"), "clock_ahead", DiscardReason.CLOCK_EDIT, parent)

    @Test
    fun confirmDisabledUntilListShownAndAllItemsRender() {
        val items = (0 until 200).map { discard(it, if (it % 2 == 0) "p1" else "p2") }
        val v = RemintView(null, items, remintedOps = 3, remintedReviews = 7)
        assertEquals(200, v.count)
        // groups: a header per parent, every item present exactly once
        assertEquals(200, discardRows(v).count { it is ListRow.Item })
        assertEquals(2, discardRows(v).count { it is ListRow.Header })

        val shown = androidx.compose.runtime.mutableStateOf(false)
        var confirmed = 0
        rule.setContent {
            RemintContent(v, mapOf("p1" to "one", "p2" to "two"), clockBlocked = false, listShown = shown.value, message = null,
                onShowList = { shown.value = true }, onConfirm = { confirmed++ })
        }
        rule.onNodeWithText(str(R.string.remint_discard_count, 200)).assertExists()
        rule.onNodeWithTag(SyncTags.REMINT_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(SyncTags.REMINT_SHOW_LIST).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SyncTags.REMINT_CONFIRM).assertIsEnabled()
        // the last item of the lazy list is reachable by scrolling
        rule.onNodeWithTag(SyncTags.REMINT_LIST).performScrollToNode(hasText("e-x199", substring = true))
        rule.onNodeWithText("e-x199", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SyncTags.REMINT_CONFIRM).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun remintHiddenWithReasonWhenPreconditionsFail() {
        val blocked = RemintView(ClockActionBlock.PENDING_OPS, emptyList(), 0, 0)
        assertTrue(remintGate(blocked, clockBlocked = false, listShown = true) is RemintGate.Hidden)
        rule.setContent { RemintContent(blocked, emptyMap(), false, true, null, {}, {}) }
        rule.onNodeWithText(str(R.string.remint_block_pending)).assertExists()
        rule.onNodeWithTag(SyncTags.REMINT_CONFIRM).assertDoesNotExist()

        // clock_blocked hides it even when core's preview says available
        val ok = RemintView(null, emptyList(), 1, 1)
        val gate = remintGate(ok, clockBlocked = true, listShown = true)
        assertEquals(RemintGate.Hidden(R.string.remint_block_clock_blocked), gate)
    }

    @Test
    fun nothingToDiscardNeedsNoListView() {
        assertEquals(RemintGate.Offered(true), remintGate(RemintView(null, emptyList(), 2, 0), false, false))
        assertEquals(RemintGate.Offered(false), remintGate(RemintView(null, listOf(discard(1, null)), 2, 0), false, false))
    }
}
