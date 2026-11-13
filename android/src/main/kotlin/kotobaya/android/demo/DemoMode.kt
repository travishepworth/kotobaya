package kotobaya.android.demo

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotobaya.android.decks.DeckSource
import kotobaya.android.grammar.GrammarCapturePort
import kotobaya.android.media.browse.MediaBrowsePort
import kotobaya.android.pairing.DevicesPort
import kotobaya.android.review.ReviewBackend
import kotobaya.android.search.SearchPort
import kotobaya.android.struggle.StruggleSource
import kotobaya.android.sync.SyncFlowPort
import kotobaya.android.vm.StateViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The debug "demo data" switch. When on (debug builds only), the app starts with [module] loaded after every
 * feature module, so each screen's port reads the demo dataset instead of the device's data; the real database,
 * sync and server are left alone (background sync of the real account keeps running unseen).
 */
object DemoMode {
    private const val PREFS = "kotobaya-demo"
    private const val KEY_ON = "enabled"
    private const val KEY_SCENARIO = "scenario"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    fun scenario(context: Context): String = prefs(context).getString(KEY_SCENARIO, null) ?: "default"

    /** Stores the switch (synchronously: the app restarts right after). */
    fun set(context: Context, enabled: Boolean, scenario: String) {
        prefs(context).edit().putBoolean(KEY_ON, enabled).putString(KEY_SCENARIO, scenario).commit()
    }

    /** The port overrides of demo mode for [scenario]; the dataset is read on first use. */
    fun module(context: Context, scenario: String): Module = module {
        single { DemoData.load(context.assets, scenario) }
        single { DemoStore(get()) }
        single<ReviewBackend<*>> { get<DemoStore>().reviewBackend() }
        single<SearchPort> { DemoSearchPort(get()) }
        factory<DeckSource> { DemoDeckSource(get()) }
        factory<MediaBrowsePort> { DemoMediaBrowsePort(get()) }
        single<StruggleSource> { DemoStruggleSource(get()) }
        single<SyncFlowPort> { DemoSyncFlowPort(get()) }
        factory<DevicesPort> { DemoDevicesPort(get()) }
        factory<GrammarCapturePort> { DemoGrammarCapturePort }
    }
}

/** Settings > Demo data (debug builds). */
data class DemoSettingsUiState(
    /** This build carries the dataset (false: the section says so). */
    val available: Boolean = false,
    val enabled: Boolean = false,
    val scenario: String = "default",
)

interface DemoSettingsIntents {
    fun setEnabled(enabled: Boolean) {}
    fun setScenario(scenario: String) {}
}

/** Reads and stores the switch on IO; any change restarts the app ([restart]) so the Koin graph is rebuilt. */
class DemoSettingsViewModel(
    private val context: Context,
    private val restart: () -> Unit,
    io: CoroutineDispatcher = Dispatchers.IO,
) : StateViewModel<DemoSettingsUiState>(DemoSettingsUiState(), io), DemoSettingsIntents {
    init {
        launchIo {
            val available = DemoData.available(context.assets)
            update { DemoSettingsUiState(available, DemoMode.enabled(context), DemoMode.scenario(context)) }
        }
    }

    private fun apply(enabled: Boolean, scenario: String) {
        val restartNeeded = enabled || current.enabled
        update { it.copy(enabled = enabled, scenario = scenario) }
        launchIo {
            DemoMode.set(context, enabled, scenario)
            if (restartNeeded) restart()
        }
    }

    override fun setEnabled(enabled: Boolean) = apply(enabled, current.scenario)

    override fun setScenario(scenario: String) {
        if (scenario != current.scenario) apply(current.enabled, scenario)
    }
}
