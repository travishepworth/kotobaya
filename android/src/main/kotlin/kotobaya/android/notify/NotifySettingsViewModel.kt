package kotobaya.android.notify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.vm.StateViewModel

/** The hour of the evening reminder the switch turns on. */
const val EVENING_DEFAULT_HOUR: Int = 20

data class NotifySettingsUiState(
    val nudges: Boolean = false,
    /** The evening reminder is on (at [EVENING_DEFAULT_HOUR]). */
    val evening: Boolean = false,
)

interface NotifySettingsIntents {
    fun setNudges(enabled: Boolean) {}
    fun setEvening(enabled: Boolean) {}
}

/** Settings > Notifications: the nudge and evening-reminder switches over [NotifyPrefs] (read and written on IO). */
class NotifySettingsViewModel(
    private val prefs: NotifyPrefs,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<NotifySettingsUiState>(NotifySettingsUiState(), io), NotifySettingsIntents {
    init {
        launchIo {
            val nudges = prefs.nudgesEnabled
            val evening = prefs.eveningHour != null
            update { NotifySettingsUiState(nudges, evening) }
        }
    }

    override fun setNudges(enabled: Boolean) {
        update { it.copy(nudges = enabled) }
        launchIo { prefs.nudgesEnabled = enabled }
    }

    override fun setEvening(enabled: Boolean) {
        update { it.copy(evening = enabled) }
        launchIo { prefs.eveningHour = if (enabled) EVENING_DEFAULT_HOUR else null }
    }
}
