package kotobaya.android.search

import app.cash.sqldelight.db.SqlDriver
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.dict.InstalledDictionary
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.android.sync.SyncBootstrap
import kotobaya.android.word.WordRepository
import kotobaya.db.app.AppDatabase
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** My-cards search: the replica when usable, the server otherwise (also when the local run throws). */
class CardSearchBackend(
    private val local: LocalCardSearch,
    private val remote: ApiCardSearch,
    private val replicaUsable: () -> Boolean,
) {
    suspend fun search(query: String): CardSearchOutcome {
        if (replicaUsable()) {
            val r = runCatching { local.search(query) }.getOrNull()
            if (r != null) return r
        }
        return remote.search(query)
    }
}

private val searchModule = module {
    single(named("searchApi")) {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        ApiClient({ settings.serverBaseUrl }, { tokens.token })
    }
    single {
        WordSearch(get<InstalledDictionary>(), get<SqlDriver>(named("syncAppDriver")), get<ApiClient>(named("searchApi")))
    }
    single {
        val koin = getKoin()
        // Usable once the startup replay has committed (the replica then holds every acknowledged op).
        val usable = { koin.getOrNull<SyncBootstrap>()?.ready?.isCompleted == true }
        CardSearchBackend(
            LocalCardSearch(get<SqlDriver>(named("syncAppDriver")), get<AppDatabase>()),
            ApiCardSearch(get<ApiClient>(named("searchApi"))),
            usable,
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
    routes = listOf(NavRoute(Routes.SEARCH, R.string.nav_search, topLevel = true) { SearchScreen() }),
)
