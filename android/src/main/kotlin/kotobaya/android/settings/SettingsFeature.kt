package kotobaya.android.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.di.SettingsSection
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.net.ApiClient
import kotobaya.android.settings.ui.AboutSection
import kotobaya.android.settings.ui.SettingsScreen
import kotobaya.android.settings.ui.StudySection
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncTriggers
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.mint.OpMinter
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.dsl.module

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (_: PackageManager.NameNotFoundException) {
    "?"
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

@Composable
private fun StudyRoute() {
    val koin = getKoin()
    val vm: StudySettingsViewModel = viewModel { StudySettingsViewModel(koin.get()) }
    val state by vm.state.collectAsState()
    StudySection(state, vm)
}

@Composable
private fun AboutRoute() {
    val koin = getKoin()
    val context = LocalContext.current
    val vm: AboutViewModel = viewModel {
        AboutViewModel(appVersion(context), context.packageName, {
            koin.get<ApiClient>().get("/health").body.optString("version").ifEmpty { null }
        })
    }
    val state by vm.state.collectAsState()
    AboutSection(state)
}

/** THE registration for featureEntries: the Settings tab plus the Study and About sections. */
val settingsUiFeature = FeatureEntry(
    id = "settings-ui",
    module = settingsUiModule,
    routes = listOf(NavRoute(Routes.SETTINGS, R.string.nav_settings, topLevel = true) { SettingsRoute() }),
    settingsSections = listOf(
        SettingsSection("study", R.string.study_title) { StudyRoute() },
        SettingsSection("about", R.string.about_title) { AboutRoute() },
    ),
)
