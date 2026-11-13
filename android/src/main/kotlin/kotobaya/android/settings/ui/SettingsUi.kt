package kotobaya.android.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.settings.AboutUiState
import kotobaya.android.settings.StudyKey
import kotobaya.android.settings.StudySettingsIntents
import kotobaya.android.settings.StudySettingsUiState

/** Section order: devices (pairing) first, then sync, the rest in registry order, about last. */
fun orderedSections(sections: List<SettingsSection>): List<SettingsSection> {
    val first = listOf("devices", "sync")
    return sections.sortedBy { s ->
        when {
            s.id in first -> first.indexOf(s.id)
            s.id == "about" -> Int.MAX_VALUE
            else -> first.size
        }
    }
}

/** The Settings tab: every registered section, one after the other (stable sort keeps registry order). */
@Composable
fun SettingsScreen(sections: List<SettingsSection>) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        orderedSections(sections).forEach { s ->
            Text(T(s.titleRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 16.dp))
            s.content()
            HorizontalDivider()
        }
    }
}

/** Settings > Study. */
@Composable
fun StudySection(state: StudySettingsUiState, intents: StudySettingsIntents) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        listOf(
            StudyKey.NEW_PER_DAY to R.string.study_new_per_day,
            StudyKey.REVIEWS_PER_DAY to R.string.study_reviews_per_day,
            StudyKey.ROLLOVER_HOUR to R.string.study_rollover_hour,
        ).forEach { (k, label) ->
            OutlinedTextField(state.fields[k].orEmpty(), { intents.setField(k, it) }, label = { Text(T(label)) }, singleLine = true)
        }
        Button(onClick = intents::save) { Text(T(R.string.study_save)) }
        state.message?.let { Text(T(it)) }
    }
}

/** Settings > About. */
@Composable
fun AboutSection(state: AboutUiState) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(T(R.string.about_version, state.appVersion))
        Text(T(R.string.about_flavor, T(state.flavorRes)))
        Text(state.serverVersion?.let { T(R.string.about_server_version, it) } ?: T(R.string.about_server_unknown))
    }
}
