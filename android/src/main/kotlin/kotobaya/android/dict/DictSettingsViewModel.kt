package kotobaya.android.dict

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotobaya.android.vm.StateViewModel

/** What Settings > Offline storage reads and does. */
interface DictSettingsPort {
    val installState: StateFlow<InstallState>

    /** Bumped whenever the installed file is swapped. */
    val generation: StateFlow<Long>

    fun scope(): String

    fun setScope(scope: String)

    /** Startup cleanup and the daily update check (safe on every open). */
    suspend fun startup()

    fun info(): PackageInfo?

    fun installedBytes(): Long

    /** Re-reads the installed dictionary's info (after an install). */
    fun refreshDictionary()

    suspend fun runUpdate()
}

/** [DictSettingsPort] over the app's store, installer and settings. */
class AppDictSettingsPort(
    private val store: DictStore,
    private val installer: DictInstaller,
    private val settings: kotobaya.android.settings.SettingsStore,
    private val dictionary: InstalledDictionary,
    private val ensurePeriodic: () -> Unit,
) : DictSettingsPort {
    override val installState: StateFlow<InstallState> get() = installer.state
    override val generation: StateFlow<Long> get() = store.generation

    override fun scope(): String = settings.dictionaryScope

    override fun setScope(scope: String) {
        settings.dictionaryScope = scope
    }

    override suspend fun startup() {
        installer.startup()
        ensurePeriodic()
    }

    override fun info() = store.info()

    override fun installedBytes() = store.installedBytes()

    override fun refreshDictionary() {
        dictionary.info()
    }

    override suspend fun runUpdate() {
        installer.run()
    }
}

data class DictSettingsUiState(
    val info: PackageInfo? = null,
    val bytes: Long = 0L,
    /** The chosen package scope: `top60k` or `all`. */
    val scope: String = "",
    val install: InstallState = InstallState.Idle,
)

interface DictSettingsIntents {
    fun chooseScope(scope: String) {}
    fun updateNow() {}
}

/** Settings > Offline storage: scope, installed release, sizes, "update now". */
class DictSettingsViewModel(
    private val port: DictSettingsPort,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<DictSettingsUiState>(DictSettingsUiState(install = port.installState.value), io), DictSettingsIntents {
    init {
        launchIo {
            val scope = port.scope()
            update { it.copy(scope = scope) }
        }
        viewModelScope.launch {
            port.generation.collect {
                launchIo {
                    port.startup()
                    val info = port.info()
                    val bytes = port.installedBytes()
                    update { it.copy(info = info, bytes = bytes) }
                }
            }
        }
        viewModelScope.launch {
            port.installState.collect { s ->
                update { it.copy(install = s) }
                if (s is InstallState.Finished) {
                    launchIo {
                        val info = port.info().also { port.refreshDictionary() }
                        update { it.copy(info = info) }
                    }
                }
            }
        }
    }

    override fun chooseScope(scope: String) {
        update { it.copy(scope = scope) }
        launchIo { port.setScope(scope) }
    }

    override fun updateNow() {
        launchIo {
            port.runUpdate()
            val info = port.info()
            update { it.copy(info = info) }
        }
    }
}
