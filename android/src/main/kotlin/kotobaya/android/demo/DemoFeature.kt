package kotobaya.android.demo

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.BuildConfig
import kotobaya.android.R
import kotobaya.android.demo.ui.DemoSection
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection

/** Relaunches the app (a fresh process, so the Koin graph is built with or without the demo module). */
private fun restartApp(context: Context) {
    val app = context.applicationContext
    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

@Composable
private fun DemoRoute() {
    val context = LocalContext.current.applicationContext
    val vm: DemoSettingsViewModel = viewModel { DemoSettingsViewModel(context, restart = { restartApp(context) }) }
    val state by vm.state.collectAsState()
    DemoSection(state, vm)
}

/** THE registration for featureEntries: the debug-only Settings section of the demo switch. */
val demoFeature = FeatureEntry(
    id = "demo",
    settingsSections = if (BuildConfig.DEBUG) listOf(SettingsSection("demo", R.string.demo_section_title) { DemoRoute() }) else emptyList(),
)
