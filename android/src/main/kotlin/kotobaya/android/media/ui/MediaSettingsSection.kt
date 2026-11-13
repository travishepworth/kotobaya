package kotobaya.android.media.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.media.MediaSettingsIntents
import kotobaya.android.media.MediaSettingsUiState
import kotobaya.android.settings.ui.SettingsBody
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbFactRow
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSegmented
import kotobaya.android.ui.components.KbSkeletonRows
import kotobaya.android.ui.components.KbSwitchRow
import kotobaya.android.ui.theme.KbTheme

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

/**
 * Settings › Audio and offline (moved from the old Media tab, DESIGN §4.2): what the cache holds, Clear cache, and
 * the prefetch window, budget and mobile-data switch.
 */
@Composable
fun MediaSettingsSection(state: MediaSettingsUiState, intents: MediaSettingsIntents) {
    val c = KbTheme.colors
    SettingsBody {
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            val b = state.breakdown
            if (b == null) {
                KbSkeletonRows(3)
            } else {
                KbFactRow(T(R.string.media_audio_label), T(R.string.media_audio_value, mb(b.cardAudioBytes), b.cardAudioCount))
                KbDivider(inset = 16.dp)
                KbFactRow(T(R.string.media_media_label), T(R.string.storage_mb, mb(b.mediaBytes)))
                KbDivider(inset = 16.dp)
                KbFactRow(T(R.string.media_covers_label), T(R.string.storage_mb, mb(b.coverBytes)))
                KbDivider(inset = 16.dp)
                KbFactRow(T(R.string.media_dictionary_label), T(R.string.storage_mb, mb(b.dictionaryBytes)))
                KbDivider(inset = 16.dp)
                KbFactRow(
                    T(R.string.media_total_label),
                    T(R.string.storage_mb, mb(b.totalBytes)),
                    valueStyle = KbTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            KbButton(
                T(R.string.media_clear_cache), intents::clearCache,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                variant = KbButtonVariant.Outline,
            )
        }
        KbPanel {
            Text(T(R.string.media_prefetch_days), style = KbTheme.type.titleSm, color = c.text)
            KbSegmented(
                listOf(1, 2, 3, 7).map { it to T(R.string.media_days_option, it) },
                selected = state.prefetchDays,
                onSelect = intents::setPrefetchDays,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            )
            Text(T(R.string.media_budget), style = KbTheme.type.titleSm, color = c.text, modifier = Modifier.padding(top = 20.dp))
            KbSegmented(
                listOf(250, 500, 1000, 2000).map { it to T(R.string.media_budget_option, it) },
                selected = state.budgetMb,
                onSelect = intents::setBudgetMb,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
            )
        }
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            KbSwitchRow(T(R.string.media_allow_metered), state.allowMetered, intents::setAllowMetered)
        }
    }
}
