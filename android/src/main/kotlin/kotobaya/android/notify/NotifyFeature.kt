package kotobaya.android.notify

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.nav.NavRoute
import kotobaya.android.queue.LocalQueueLoader
import org.koin.android.ext.koin.androidContext
import org.koin.compose.koinInject
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
        if (started++ == 0) trigger.fire()
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

/** Android 13+ asks for POST_NOTIFICATIONS; older versions need nothing. */
@Composable
fun NotificationRationaleScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column(Modifier.padding(16.dp)) {
        Text(T(R.string.notify_permission_title))
        Text(T(R.string.notify_permission_rationale))
        if (granted) {
            Text(T(R.string.notify_permission_granted))
        } else {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(T(R.string.notify_permission_allow)) }
            Text(T(R.string.notify_permission_denied_note))
        }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun NotifySettings() {
    val prefs: NotifyPrefs = koinInject()
    var nudges by remember { mutableStateOf(prefs.nudgesEnabled) }
    var evening by remember { mutableStateOf(prefs.eveningHour != null) }
    Column {
        NotificationRationaleScreen()
        Row(Modifier.padding(horizontal = 16.dp)) {
            Text(T(R.string.notify_nudges_enable))
            Switch(checked = nudges, onCheckedChange = { nudges = it; prefs.nudgesEnabled = it })
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            Text(T(R.string.notify_evening_enable, EVENING_DEFAULT_HOUR))
            Switch(checked = evening, onCheckedChange = {
                evening = it
                prefs.eveningHour = if (it) EVENING_DEFAULT_HOUR else null
            })
        }
    }
}

private const val EVENING_DEFAULT_HOUR = 20

val notifyFeature = FeatureEntry(
    id = "notify",
    module = notifyModule,
    routes = listOf(NavRoute(NOTIFY_PERMISSION_ROUTE, R.string.notify_permission_title) { NotificationRationaleScreen() }),
    settingsSections = listOf(SettingsSection("notify", R.string.notify_section_title) { NotifySettings() }),
)
