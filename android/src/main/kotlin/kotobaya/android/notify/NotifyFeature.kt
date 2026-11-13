package kotobaya.android.notify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.nav.NavRoute
import kotobaya.android.notify.ui.NotificationRationaleScreen
import kotobaya.android.notify.ui.NotifySettings
import kotobaya.android.queue.LocalQueueLoader
import org.koin.android.ext.koin.androidContext
import org.koin.compose.getKoin
import org.koin.dsl.binds
import org.koin.dsl.module

/** Route of the rationale screen; the onboarding flow navigates here on Android 13+. */
const val NOTIFY_PERMISSION_ROUTE = "notify_permission"

/** Fires [DueTrigger] whenever the app comes to the foreground (app open). */
internal class AppOpenHook(app: Application, private val trigger: DueTrigger) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    init {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) trigger.fireInBackground() // runs on the main thread (BUG-8)
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

val notifyModule = module {
    single { NotifyPrefs(androidContext()) }
    single<NotifySignals> { SqliteSignals(androidContext()) }
    single { DueScheduler(androidContext(), get()) }
    single {
        DueNotifications(androidContext(), get(), get<LocalQueueLoader>(), get(), get(), get())
    } binds arrayOf(DueListener::class)
    single { DueTrigger { getAll<DueListener>() } }
    single(createdAtStart = true) { AppOpenHook(androidContext() as Application, get()) }
}

@Composable
private fun NotifySettingsRoute() {
    val koin = getKoin()
    val vm: NotifySettingsViewModel = viewModel { NotifySettingsViewModel(koin.get()) }
    val state by vm.state.collectAsState()
    NotifySettings(state, vm)
}

val notifyFeature = FeatureEntry(
    id = "notify",
    module = notifyModule,
    routes = listOf(NavRoute(NOTIFY_PERMISSION_ROUTE, R.string.notify_permission_title) { NotificationRationaleScreen() }),
    settingsSections = listOf(SettingsSection("notify", R.string.notify_section_title) { NotifySettingsRoute() }),
)
