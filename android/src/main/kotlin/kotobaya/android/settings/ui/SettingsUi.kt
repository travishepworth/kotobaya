package kotobaya.android.settings.ui

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.PairingRepository
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncTriggers
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.mint.OpMinter
import org.koin.compose.koinInject
import org.koin.dsl.module

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

@Composable
private fun StudySection() {
    val model = koinInject<StudySettingsModel>()
    val scope = rememberCoroutineScope()
    val fields = remember { StudyKey.entries.associateWith { mutableStateOf("") } }
    var message by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { StudyKey.entries.associateWith { model.valueOf(it) } }
        loaded.forEach { (k, v) -> fields.getValue(k).value = v.toString() }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        listOf(
            StudyKey.NEW_PER_DAY to R.string.study_new_per_day,
            StudyKey.REVIEWS_PER_DAY to R.string.study_reviews_per_day,
            StudyKey.ROLLOVER_HOUR to R.string.study_rollover_hour,
        ).forEach { (k, label) ->
            val state = fields.getValue(k)
            OutlinedTextField(state.value, { state.value = it }, label = { Text(T(label)) }, singleLine = true)
        }
        Button(onClick = {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    var out: Int = R.string.study_unchanged
                    for ((k, st) in fields) {
                        val n = st.value.trim().toLongOrNull()
                        val r = if (n == null) StudyResult.OutOfRange else model.set(k, n)
                        when (r) {
                            StudyResult.OutOfRange -> return@withContext R.string.study_invalid
                            is StudyResult.Refused -> return@withContext R.string.study_refused
                            StudyResult.Saved -> out = R.string.study_saved
                            StudyResult.Unchanged -> Unit
                        }
                    }
                    out
                }
                message = res
            }
        }) { Text(T(R.string.study_save)) }
        message?.let { Text(T(it)) }
    }
}

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (_: PackageManager.NameNotFoundException) {
    "?"
}

/** The `ankidroid` flavor installs as com.ichi2.anki (build.gradle.kts); the fallback keeps our own id. */
fun flavorLabelRes(packageName: String): Int =
    if (packageName == "com.ichi2.anki") R.string.about_flavor_ankidroid else R.string.about_flavor_own

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val api = koinInject<ApiClient>()
    var server by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        server = runCatching { api.get("/health").body.optString("version").ifEmpty { null } }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(T(R.string.about_version, appVersion(context)))
        Text(T(R.string.about_flavor, T(flavorLabelRes(context.packageName))))
        Text(server?.let { T(R.string.about_server_version, it) } ?: T(R.string.about_server_unknown))
    }
}

/** True while this device has no token/server (Home shows the route to pairing). */
@Composable
fun rememberIsUnpaired(): Boolean {
    val repo = koinInject<PairingRepository>()
    return remember { !repo.isPaired }
}

private val settingsUiModule = module {
    single {
        val db = get<AppDatabase>()
        val minter = get<OpMinter<SqlLearningTables>>()
        val triggers = get<SyncTriggers>()
        StudySettingsModel(
            readRaw = { db.localQueueQueries.schedulerSettings().executeAsList().associate { it.key to it.value_ } },
            mint = { type, payload -> minter.mint(type, payload) },
            onMinted = { triggers.localChange() },
        )
    }
}

@Composable
private fun SettingsRoute() {
    val registry = koinInject<FeatureRegistry>()
    SettingsScreen(registry.settingsSections)
}

/** THE registration for featureEntries: the Settings tab plus the Study and About sections. */
val settingsUiFeature = FeatureEntry(
    id = "settings-ui",
    module = settingsUiModule,
    routes = listOf(NavRoute(Routes.SETTINGS, R.string.nav_settings, topLevel = true) { SettingsRoute() }),
    settingsSections = listOf(
        SettingsSection("study", R.string.study_title) { StudySection() },
        SettingsSection("about", R.string.about_title) { AboutSection() },
    ),
)
