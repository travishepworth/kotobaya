package kotobaya.android.dict

import kotlinx.coroutines.flow.StateFlow

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
