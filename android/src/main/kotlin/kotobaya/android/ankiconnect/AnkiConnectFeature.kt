package kotobaya.android.ankiconnect

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotobaya.android.R
import kotobaya.android.ankiapi.store.TakobotoAnkiStore
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.media.BlobStore
import kotobaya.android.sync.SqlLearningTables
import org.koin.compose.koinInject
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
private fun AnkiConnectSettings() {
    val prefs: AnkiConnectPrefs = koinInject()
    val controller: AnkiConnectController = koinInject()
    val context: Context = koinInject()
    val status by controller.status.collectAsState()
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var key by remember { mutableStateOf(prefs.apiKey) }
    var origins by remember { mutableStateOf(prefs.origins) }
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs.enabled = it
                controller.apply(context)
            })
            Text(T(R.string.ankiconnect_enable), Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(
            port,
            { v ->
                port = v.filter(Char::isDigit).take(5)
                port.toIntOrNull()?.takeIf { it in 1..65535 }?.let { prefs.port = it }
            },
            Modifier.fillMaxWidth(),
            label = { Text(T(R.string.ankiconnect_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        OutlinedTextField(
            key, { key = it; prefs.apiKey = it }, Modifier.fillMaxWidth(),
            label = { Text(T(R.string.ankiconnect_api_key)) }, singleLine = true,
        )
        OutlinedTextField(
            origins, { origins = it; prefs.origins = it }, Modifier.fillMaxWidth(),
            label = { Text(T(R.string.ankiconnect_origins)) },
        )
        Text(
            when (val s = status) {
                AnkiConnectStatus.Stopped -> T(R.string.ankiconnect_status_stopped)
                is AnkiConnectStatus.Running -> T(R.string.ankiconnect_status_running, s.port)
                is AnkiConnectStatus.Failed -> T(R.string.ankiconnect_status_failed, s.message)
            },
        )
        Text(T(R.string.ankiconnect_hint, prefs.port))
    }
}

/** THE registration for featureEntries. */
val ankiConnectFeature = FeatureEntry(
    id = "ankiconnect",
    module = ankiConnectModule,
    settingsSections = listOf(SettingsSection("ankiconnect", R.string.ankiconnect_section_title) { AnkiConnectSettings() }),
)
