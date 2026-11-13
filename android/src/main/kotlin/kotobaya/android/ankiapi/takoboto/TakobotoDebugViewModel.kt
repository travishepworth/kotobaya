package kotobaya.android.ankiapi.takoboto

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.ankiapi.provider.ProviderCallLog
import kotobaya.android.vm.StateViewModel

/** Settings > Takoboto debug: provider calls, note counters, whether the Takoboto note type exists. */
data class TakobotoDebugUiState(
    val calls: List<String> = emptyList(),
    val added: Long = 0,
    val duplicates: Long = 0,
    val refused: Long = 0,
    val noteTypeReady: Boolean = false,
) {
    /** Calls the provider did not recognise. */
    val unknown: List<String> get() = calls.filter { ProviderCallLog.UNKNOWN_MARK in it }
}

interface TakobotoDebugIntents {
    fun refresh() {}
}

/** Reads the debug data on IO ([load] touches both databases); [refresh] reads again. */
class TakobotoDebugViewModel(
    private val load: () -> TakobotoDebugUiState,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<TakobotoDebugUiState>(TakobotoDebugUiState(), io), TakobotoDebugIntents {
    init {
        refresh()
    }

    override fun refresh() {
        launchIo {
            val data = load()
            update { data }
        }
    }
}
