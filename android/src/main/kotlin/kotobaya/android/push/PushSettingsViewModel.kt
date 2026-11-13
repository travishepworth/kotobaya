package kotobaya.android.push

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.vm.StateViewModel

/** What the push section says. */
enum class PushUi { OFF, NO_DISTRIBUTOR, WAITING, ON }

data class PushSettingsUiState(
    val ui: PushUi = PushUi.OFF,
    /** The distributor gave this device an endpoint (Disable / Retry are offered instead of Enable). */
    val hasEndpoint: Boolean = false,
)

interface PushSettingsIntents {
    fun enable() {}
    fun retry() {}
    fun disable() {}
}

/** Settings > Push: UnifiedPush (ntfy) registration over [PushPrefs] and [PushRegistrar]; all on IO. */
class PushSettingsViewModel(
    private val prefs: PushPrefs,
    private val registrar: PushRegistrar,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<PushSettingsUiState>(PushSettingsUiState(), io), PushSettingsIntents {
    init {
        launchIo {
            val ui = when {
                prefs.endpoint == null -> PushUi.OFF
                prefs.registeredWithServer -> PushUi.ON
                else -> PushUi.WAITING
            }
            update { PushSettingsUiState(ui, prefs.endpoint != null) }
        }
    }

    override fun enable() {
        launchIo {
            val ui = when (registrar.enable()) {
                EnableResult.NoDistributor -> PushUi.NO_DISTRIBUTOR
                EnableResult.Requested -> PushUi.WAITING
            }
            update { PushSettingsUiState(ui, prefs.endpoint != null) }
        }
    }

    override fun retry() {
        launchIo { registrar.retryServerRegistration() }
    }

    override fun disable() {
        launchIo {
            registrar.disable()
            update { PushSettingsUiState(PushUi.OFF, prefs.endpoint != null) }
        }
    }
}
