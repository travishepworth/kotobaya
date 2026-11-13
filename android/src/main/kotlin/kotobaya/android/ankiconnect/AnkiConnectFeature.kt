package kotobaya.android.ankiconnect

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.ankiconnect.ui.AnkiConnectSettings
import org.koin.compose.getKoin
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotobaya.android.R
import kotobaya.android.ankiapi.store.TakobotoAnkiStore
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.media.BlobStore
import kotobaya.android.sync.SqlLearningTables
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

/** User settings of the on-device AnkiConnect server. */
class AnkiConnectPrefs(private val p: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("kotobaya-ankiconnect", Context.MODE_PRIVATE))

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var port: Int
        get() = p.getInt("port", DEFAULT_PORT)
        set(v) = p.edit().putInt("port", v).apply()

    /** Empty = no key required. */
    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) = p.edit().putString("api_key", v).apply()

    /** Comma-separated origin patterns (see [AnkiConnectHttp.originAllowed]). */
    var origins: String
        get() = p.getString("origins", DEFAULT_ORIGINS) ?: DEFAULT_ORIGINS
        set(v) = p.edit().putString("origins", v).apply()

    fun originList(): List<String> = origins.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_ORIGINS = "http://localhost,http://127.0.0.1,moz-extension://*,chrome-extension://*"
    }
}

sealed interface AnkiConnectStatus {
    data object Stopped : AnkiConnectStatus

    data class Running(val port: Int) : AnkiConnectStatus

    data class Failed(val message: String) : AnkiConnectStatus
}

/** Owns the server: starts it on the configured port and publishes [status]. */
class AnkiConnectController(private val prefs: AnkiConnectPrefs, backend: AnkiConnectBackend) {
    private val server = AnkiConnectServer(
        AnkiConnectHttp(AnkiConnectProtocol(backend) { prefs.apiKey.ifEmpty { null } }) { prefs.originList() },
    )
    private val _status = MutableStateFlow<AnkiConnectStatus>(AnkiConnectStatus.Stopped)
    val status: StateFlow<AnkiConnectStatus> get() = _status

    @Synchronized
    fun start() {
        _status.value = try {
            AnkiConnectStatus.Running(server.start(prefs.port))
        } catch (e: IOException) {
            AnkiConnectStatus.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    @Synchronized
    fun stop() {
        server.stop()
        _status.value = AnkiConnectStatus.Stopped
    }

    /** Applies [prefs]: starts, restarts (new port) or stops the server and its service. */
    fun apply(context: Context) {
        val intent = Intent(context, AnkiConnectService::class.java)
        if (prefs.enabled) {
            runCatching { context.startService(intent) }.onFailure { start() }
        } else {
            context.stopService(intent)
            stop()
        }
    }
}

/**
 * Keeps the process around while the server runs (not a foreground service: the user toggles the
 * server on, and Yomitan is used with the app recently opened). The server itself is the [AnkiConnectController]'s.
 */
class AnkiConnectService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GlobalContext.get().get<AnkiConnectController>().start()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { GlobalContext.get().get<AnkiConnectController>().stop() }
        super.onDestroy()
    }
}

private val ankiConnectModule = module {
    single { AnkiConnectPrefs(get<Context>()) }
    single<AnkiConnectBackend> {
        StoreAnkiConnectBackend(
            get<TakobotoAnkiStore<SqlLearningTables>>(),
            BlobMediaSink(get<BlobStore>()),
        )
    }
    single { AnkiConnectController(get(), get()) }
}

@Composable
private fun AnkiConnectSettingsRoute() {
    val koin = getKoin()
    val vm: AnkiConnectSettingsViewModel = viewModel {
        val controller = koin.get<AnkiConnectController>()
        val context = koin.get<Context>()
        AnkiConnectSettingsViewModel(koin.get(), controller.status, apply = { controller.apply(context) })
    }
    val state by vm.state.collectAsState()
    AnkiConnectSettings(state, vm)
}

/** THE registration for featureEntries. */
val ankiConnectFeature = FeatureEntry(
    id = "ankiconnect",
    module = ankiConnectModule,
    settingsSections = listOf(SettingsSection("ankiconnect", R.string.ankiconnect_section_title) { AnkiConnectSettingsRoute() }),
)
