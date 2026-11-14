package kotobaya.android.decks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.R
import kotobaya.android.decks.ui.DecksScreen
import kotobaya.android.di.FeatureEntry
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.search.asLabeler
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.review.ReviewRoute
import kotobaya.android.sync.SyncStatusRepository
import kotobaya.android.sync.content.ContentInstallWork
import kotobaya.android.sync.content.ContentInstaller
import kotobaya.android.sync.content.ContentKind
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkManager
import kotlinx.coroutines.flow.merge
import org.koin.compose.getKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Decks tab (read-only): replaces the shell's Decks placeholder. Deck create/rename/delete are left for later. */
val decksFeature = FeatureEntry(
    id = "decks",
    module = module {
        factory<DeckSource> { DeckRepository(get<SqlDriver>(named("syncAppDriver")), get<LocalQueueLoader>(), get<InstalledDictionary>().asLabeler()) }
    },
    routes = listOf(NavRoute(Routes.DECKS, R.string.nav_decks, topLevel = true) { DecksRoute() }),
)

@Composable
private fun DecksRoute() {
    val koin = getKoin()
    val ctx = LocalContext.current.applicationContext
    val vm: DecksViewModel = viewModel {
        // AND-DECKCONTENT: an installed deck-item archive changes what a deck shows, without a sync status change
        val changes = listOfNotNull(koin.getOrNull<SyncStatusRepository>()?.status, koin.getOrNull<ContentInstaller>()?.changes)
        DecksViewModel(koin.get<DeckSource>(), changes.takeIf { it.isNotEmpty() }?.merge()) { deckId ->
            koin.getOrNull<ContentInstaller>()?.clearRejected(ContentKind.DECK_ITEMS, deckId)
            runCatching { ContentInstallWork.enqueue(WorkManager.getInstance(ctx)) }
        }
    }
    val state by vm.state.collectAsState()
    DecksScreen(state, vm) {
        ReviewRoute(deckId = state.selectedId, key = "deck-study-${state.selectedId}-${state.studyRun}", onSessionEnd = vm::sessionEnded)
    }
}
