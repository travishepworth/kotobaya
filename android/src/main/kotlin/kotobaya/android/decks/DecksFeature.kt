package kotobaya.android.decks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.R
import kotobaya.android.decks.ui.DecksScreen
import kotobaya.android.di.FeatureEntry
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.queue.LocalQueueLoader
import kotobaya.android.review.ReviewRoute
import kotobaya.android.sync.SyncStatusRepository
import org.koin.compose.getKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Decks tab (read-only): replaces the shell's Decks placeholder. Deck create/rename/delete are left for later. */
val decksFeature = FeatureEntry(
    id = "decks",
    module = module {
        factory<DeckSource> { DeckRepository(get<SqlDriver>(named("syncAppDriver")), get<LocalQueueLoader>()) }
    },
    routes = listOf(NavRoute(Routes.DECKS, R.string.nav_decks, topLevel = true) { DecksRoute() }),
)

@Composable
private fun DecksRoute() {
    val koin = getKoin()
    val vm: DecksViewModel = viewModel {
        DecksViewModel(koin.get<DeckSource>(), koin.getOrNull<SyncStatusRepository>()?.status)
    }
    val state by vm.state.collectAsState()
    DecksScreen(state, vm) {
        ReviewRoute(deckId = state.selectedId, key = "deck-study-${state.selectedId}-${state.studyRun}", onSessionEnd = vm::sessionEnded)
    }
}
