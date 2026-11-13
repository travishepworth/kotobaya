package kotobaya.android.media.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.media.MediaSettingsIntents
import kotobaya.android.media.MediaSettingsUiState

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

/** Settings > Offline media: what the blob cache holds, Clear cache, and the prefetch window, budget and metered pref. */
@Composable
fun MediaSettingsSection(state: MediaSettingsUiState, intents: MediaSettingsIntents) {
    Column(Modifier.padding(8.dp)) {
        state.breakdown?.let { b ->
            Text(T(R.string.media_audio_size, mb(b.cardAudioBytes), b.cardAudioCount))
            Text(T(R.string.media_other_size, mb(b.mediaBytes), mb(b.coverBytes)))
            Text(T(R.string.media_dictionary_size, mb(b.dictionaryBytes)))
            Text(T(R.string.media_total_size, mb(b.totalBytes)))
        }
        TextButton(onClick = intents::clearCache) { Text(T(R.string.media_clear_cache)) }

        Text(T(R.string.media_prefetch_days))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in listOf(1, 2, 3, 7)) {
                TextButton(onClick = { intents.setPrefetchDays(d) }, enabled = state.prefetchDays != d) {
                    Text(T(R.string.media_days_option, d))
                }
            }
        }
        Text(T(R.string.media_budget))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in listOf(250, 500, 1000, 2000)) {
                TextButton(onClick = { intents.setBudgetMb(m) }, enabled = state.budgetMb != m) {
                    Text(T(R.string.media_budget_option, m))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T(R.string.media_allow_metered), Modifier.weight(1f))
            Switch(checked = state.allowMetered, onCheckedChange = intents::setAllowMetered)
        }
    }
}
