package kotobaya.android.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.di.SettingsSection
import kotobaya.android.sync.ui.SyncChipWithFlow
import kotobaya.android.sync.ui.SyncSettingsSection
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.rejected.RejectedOpsService
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source
import org.koin.compose.getKoin
import org.koin.dsl.module

val syncUiModule = module {
    single<SyncFlowPort> {
        val client = get<SyncClient>()
        val rejected = RejectedOpsService(
            client.store,
            get<OpMinter<SqlLearningTables>>(),
            Clock { System.currentTimeMillis() },
            UuidV7Source(SecureRandom().asKotlinRandom()),
        )
        SyncUiActions(get(), get(), client.store, rejected)
    }
}

/** The sync chip / flow glue: one [SyncFlowViewModel] per host screen ([key]: Home or Settings). */
@Composable
private fun rememberSyncFlow(key: String): Pair<SyncFlowViewModel, SyncFlowUiState> {
    val koin = getKoin()
    val vm: SyncFlowViewModel = viewModel(key = key) { SyncFlowViewModel(koin.get<SyncFlowPort>()) }
    val state by vm.state.collectAsState()
    return vm to state
}

/** THE registration for featureEntries (home chip, a Settings entry). */
val syncUiFeature = FeatureEntry(
    id = "sync-ui",
    module = syncUiModule,
    settingsSections = listOf(
        SettingsSection("sync", R.string.sync_title) {
            val (vm, state) = rememberSyncFlow("sync-settings")
            SyncSettingsSection(state, vm)
        },
    ),
    homeStatusChip = {
        val (vm, state) = rememberSyncFlow("sync-chip")
        SyncChipWithFlow(state, vm)
    },
)
