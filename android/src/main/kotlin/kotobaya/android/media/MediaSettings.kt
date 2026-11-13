package kotobaya.android.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.dict.DictStore
import kotobaya.android.i18n.T
import org.koin.compose.koinInject

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

/** Settings > Offline media: what the blob cache holds, Clear cache, and the prefetch window, budget and metered pref. */
@Composable
internal fun MediaSettings() {
    val store = koinInject<BlobStore>()
    val prefs = koinInject<MediaPrefs>()
    val dict = koinInject<DictStore>()
    val scope = rememberCoroutineScope()
    var breakdown by remember { mutableStateOf<StorageBreakdown?>(null) }
    var days by remember { mutableStateOf(prefs.prefetchDays) }
    var budget by remember { mutableStateOf(prefs.budgetMb) }
    var metered by remember { mutableStateOf(prefs.allowMetered) }

    fun reload() {
        scope.launch {
            breakdown = withContext(Dispatchers.IO) { store.breakdown(dict.installedBytes()) }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    Column(Modifier.padding(8.dp)) {
        breakdown?.let { b ->
            Text(T(R.string.media_audio_size, mb(b.cardAudioBytes), b.cardAudioCount))
            Text(T(R.string.media_other_size, mb(b.mediaBytes), mb(b.coverBytes)))
            Text(T(R.string.media_dictionary_size, mb(b.dictionaryBytes)))
            Text(T(R.string.media_total_size, mb(b.totalBytes)))
        }
        TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { store.clearCache() }
                reload()
            }
        }) { Text(T(R.string.media_clear_cache)) }

        Text(T(R.string.media_prefetch_days))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in listOf(1, 2, 3, 7)) {
                TextButton(onClick = { prefs.prefetchDays = d; days = d }, enabled = days != d) {
                    Text(T(R.string.media_days_option, d))
                }
            }
        }
        Text(T(R.string.media_budget))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (m in listOf(250, 500, 1000, 2000)) {
                TextButton(onClick = { prefs.budgetMb = m; budget = m }, enabled = budget != m) {
                    Text(T(R.string.media_budget_option, m))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(T(R.string.media_allow_metered), Modifier.weight(1f))
            Switch(checked = metered, onCheckedChange = { prefs.allowMetered = it; metered = it })
        }
    }
}
