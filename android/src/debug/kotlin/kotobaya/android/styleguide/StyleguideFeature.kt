package kotobaya.android.styleguide

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T

@Composable
private fun StyleguideSection() {
    val context = LocalContext.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(T(R.string.sg_section_body))
        OutlinedButton(onClick = { context.startActivity(Intent(context, StyleguideActivity::class.java)) }) {
            Text(T(R.string.sg_open))
        }
    }
}

/** Debug builds: Settings › Styleguide (debug) opens [StyleguideActivity]. The release source set has an empty entry. */
val styleguideFeature = FeatureEntry(
    id = "styleguide",
    settingsSections = listOf(SettingsSection("styleguide", R.string.sg_section_title) { StyleguideSection() }),
)
