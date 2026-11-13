package kotobaya.android.ankiconnect

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotobaya.android.vm.StateViewModel

data class AnkiConnectSettingsUiState(
    val enabled: Boolean = false,
    /** The port as typed (digits only). */
    val port: String = "",
    /** The port in effect (the hint line). */
    val savedPort: Int = AnkiConnectPrefs.DEFAULT_PORT,
    val apiKey: String = "",
    val origins: String = "",
    val status: AnkiConnectStatus = AnkiConnectStatus.Stopped,
)

interface AnkiConnectSettingsIntents {
    fun setEnabled(enabled: Boolean) {}
    fun setPort(text: String) {}
    fun setApiKey(key: String) {}
    fun setOrigins(origins: String) {}
}

/** Settings > AnkiConnect: the on-device server's switch, port, key and origins ([AnkiConnectPrefs], on IO). */
class AnkiConnectSettingsViewModel(
    private val prefs: AnkiConnectPrefs,
    status: StateFlow<AnkiConnectStatus>,
    /** Starts or stops the server to match the prefs. */
    private val apply: () -> Unit,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<AnkiConnectSettingsUiState>(AnkiConnectSettingsUiState(status = status.value), io), AnkiConnectSettingsIntents {
    init {
        launchIo {
            val enabled = prefs.enabled
            val port = prefs.port
            val key = prefs.apiKey
            val origins = prefs.origins
            update { it.copy(enabled = enabled, port = port.toString(), savedPort = port, apiKey = key, origins = origins) }
        }
        viewModelScope.launch { status.collect { s -> update { it.copy(status = s) } } }
    }

    override fun setEnabled(enabled: Boolean) {
        update { it.copy(enabled = enabled) }
        launchIo {
            prefs.enabled = enabled
            apply()
        }
    }

    override fun setPort(text: String) {
        val digits = text.filter(Char::isDigit).take(5)
        val valid = digits.toIntOrNull()?.takeIf { it in 1..65535 }
        update { it.copy(port = digits, savedPort = valid ?: it.savedPort) }
        if (valid != null) launchIo { prefs.port = valid }
    }

    override fun setApiKey(key: String) {
        update { it.copy(apiKey = key) }
        launchIo { prefs.apiKey = key }
    }

    override fun setOrigins(origins: String) {
        update { it.copy(origins = origins) }
        launchIo { prefs.origins = origins }
    }
}
