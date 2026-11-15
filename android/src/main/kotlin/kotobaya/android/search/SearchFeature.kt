package kotobaya.android.search

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.search.ui.SearchScreen
import org.koin.compose.getKoin
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.nav.DictJump
import kotobaya.android.review.InstalledKanjiWords
import kotobaya.android.review.SqlBackExtrasIndex
import kotobaya.android.nav.DictTarget
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.android.sync.SyncBootstrap
import kotobaya.android.word.WordRepository
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotobaya.android.decks.DeckMembership
import kotobaya.android.notes.NoteCreator
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.android.sync.SqlLearningTables
import kotobaya.android.sync.SyncTriggers
import kotobaya.db.app.AppDatabase
import kotobaya.sync.client.mint.OpMinter
import kotobaya.sync.client.store.Clock
import kotobaya.sync.client.store.UuidV7Source
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** My-cards search: the replica when usable, the server otherwise (also when the local run throws). */
class CardSearchBackend(
    private val local: LocalCardSearch,
    private val remote: ApiCardSearch,
    private val replicaUsable: () -> Boolean,
    private val labeler: EntryLabeler? = null,
) {
    suspend fun search(query: String): CardSearchOutcome {
        val raw = run {
            if (replicaUsable()) runCatching { local.search(query) }.getOrNull() else null
        } ?: remote.search(query)
        val results = raw as? CardSearchOutcome.Results ?: return raw
        val l = labeler ?: return raw
        val labels = withContext(Dispatchers.IO) { l.labels(results.hits.mapNotNull { it.entrySeq }) }
        return results.copy(hits = results.hits.map { it.withLabels(labels) })
    }
}

private val searchModule = module {
    single(named("searchApi")) {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        ApiClient({ settings.serverBaseUrl }, { tokens.token })
    }
    single {
        EntryLabelCache(java.io.File(get<android.content.Context>().filesDir, "entry_labels.tsv"), get<ApiClient>(named("searchApi")).asHeadwordFetcher())
    }
    single {
        WordSearch(get<InstalledDictionary>(), get<SqlDriver>(named("syncAppDriver")), get<ApiClient>(named("searchApi")))
    }
    single {
        val koin = getKoin()
        // Usable once the startup replay has committed (the replica then holds every acknowledged op).
        val usable = { koin.getOrNull<SyncBootstrap>()?.ready?.isCompleted == true }
        CardSearchBackend(
            LocalCardSearch(get<SqlDriver>(named("syncAppDriver")), get<AppDatabase>(), dictionary = get<InstalledDictionary>()),
            ApiCardSearch(get<ApiClient>(named("searchApi"))),
            usable,
            get<InstalledDictionary>().asLabeler().then(get<EntryLabelCache>()),
        )
    }
    single<SearchPort> {
        val koin = getKoin()
        AppSearchPort(
            get(), get(), get(),
            MyCardsLookup(get<SqlDriver>(named("syncAppDriver"))) { koin.getOrNull<SyncBootstrap>()?.ready?.isCompleted == true },
            SearchDeckOps(
                DeckMembership(
                    get<SqlDelightDeviceStore>(), get<OpMinter<SqlLearningTables>>(),
                    UuidV7Source(SecureRandom().asKotlinRandom()), Clock { System.currentTimeMillis() },
                ) { koin.getOrNull<SyncTriggers>()?.localChange() },
                get<NoteCreator<SqlLearningTables>>(),
            ),
            KanjiDecksLookup.over(SqlBackExtrasIndex(get<SqlDriver>(named("syncAppDriver"))), InstalledKanjiWords(get<InstalledDictionary>())),
        )
    }
    single {
        val koin = getKoin()
        WordRepository(
            get<InstalledDictionary>(), get<SqlDriver>(named("syncAppDriver")), get<ApiClient>(named("searchApi")),
            { koin.getOrNull<SyncBootstrap>()?.ready?.isCompleted == true },
        )
    }
}

/** Replaces the shell's Search placeholder. */
val searchFeature = FeatureEntry(
    id = "search",
    module = searchModule,
    routes = listOf(NavRoute(Routes.SEARCH, R.string.nav_search, topLevel = true) { SearchRoute() }),
)

@Composable
private fun SearchRoute() {
    val koin = getKoin()
    val vm: SearchViewModel = viewModel { SearchViewModel(koin.get<SearchPort>()) }
    val state by vm.state.collectAsState()
    val jump by DictJump.pending.collectAsState()
    LaunchedEffect(jump) {
        when (val t = jump) {
            is DictTarget.Word -> vm.openWord(t.seq)
            is DictTarget.Kanji -> {
                vm.setQuery(t.literal)
                vm.search()
            }
            null -> return@LaunchedEffect
        }
        DictJump.consume()
    }
    SearchScreen(state, vm)
}
