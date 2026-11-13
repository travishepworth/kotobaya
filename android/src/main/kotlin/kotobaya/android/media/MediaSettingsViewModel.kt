package kotobaya.android.media

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.dict.DictStore
import kotobaya.android.vm.StateViewModel

/** What Settings > Offline media reads and does (blocking; run on IO). */
interface MediaSettingsPort {
    fun breakdown(): StorageBreakdown

    fun clearCache()

    var prefetchDays: Int
    var budgetMb: Int
    var allowMetered: Boolean
}

/** [MediaSettingsPort] over the blob cache, the dictionary's size and [MediaPrefs]. */
class AppMediaSettingsPort(private val store: BlobStore, private val dict: DictStore, private val prefs: MediaPrefs) : MediaSettingsPort {
    override fun breakdown() = store.breakdown(dict.installedBytes())

    override fun clearCache() = store.clearCache()

    override var prefetchDays: Int by prefs::prefetchDays
    override var budgetMb: Int by prefs::budgetMb
    override var allowMetered: Boolean by prefs::allowMetered
}

data class MediaSettingsUiState(
    /** Null while loading. */
    val breakdown: StorageBreakdown? = null,
    val prefetchDays: Int = 0,
    val budgetMb: Int = 0,
    val allowMetered: Boolean = false,
)

interface MediaSettingsIntents {
    fun clearCache() {}
    fun setPrefetchDays(days: Int) {}
    fun setBudgetMb(mb: Int) {}
    fun setAllowMetered(allowed: Boolean) {}
}

/** Settings > Offline media: what the blob cache holds, Clear cache, and the prefetch window, budget and metered pref. */
class MediaSettingsViewModel(
    private val port: MediaSettingsPort,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<MediaSettingsUiState>(MediaSettingsUiState(), io), MediaSettingsIntents {
    init {
        launchIo {
            val days = port.prefetchDays
            val budget = port.budgetMb
            val metered = port.allowMetered
            update { it.copy(prefetchDays = days, budgetMb = budget, allowMetered = metered) }
        }
        reload()
    }

    private fun reload() {
        launchIo {
            val b = port.breakdown()
            update { it.copy(breakdown = b) }
        }
    }

    override fun clearCache() {
        launchIo {
            port.clearCache()
            val b = port.breakdown()
            update { it.copy(breakdown = b) }
        }
    }

    override fun setPrefetchDays(days: Int) {
        update { it.copy(prefetchDays = days) }
        launchIo { port.prefetchDays = days }
    }

    override fun setBudgetMb(mb: Int) {
        update { it.copy(budgetMb = mb) }
        launchIo { port.budgetMb = mb }
    }

    override fun setAllowMetered(allowed: Boolean) {
        update { it.copy(allowMetered = allowed) }
        launchIo { port.allowMetered = allowed }
    }
}
