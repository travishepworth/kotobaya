package kotobaya.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T

/** Stand-in body for destinations that later tickets implement. */
@Composable
fun PlaceholderScreen(@StringRes nameRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(T(R.string.placeholder_coming_soon, T(nameRes)))
    }
}

/** Home stub. [statusChips] is the slot the sync status chip (T303e) fills. */
@Composable
fun HomeScreen(statusChips: List<@Composable () -> Unit>, unpaired: Boolean = false, onPair: () -> Unit = {}) {
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.home_title))
        if (unpaired) {
            Text(T(R.string.home_pair_prompt))
            Button(onClick = onPair) { Text(T(R.string.home_pair_action)) }
        }
        statusChips.forEach { it() }
    }
}
