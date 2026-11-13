package kotobaya.android.media.browse

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.grammar.captureEntry
import kotobaya.android.media.BlobDownloader
import kotobaya.android.media.browse.ui.MediaBrowseScreen
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.compose.getKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val browseModule = module {
    single<MediaListSource> { SqlMediaListSource(get<SqlDriver>(named("syncAppDriver"))) }
    single {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        MediaBrowseRepository(
            ApiClient({ settings.serverBaseUrl }, { tokens.token }),
            FileBrowseCache(File(get<Context>().filesDir, "media-browse")),
        )
    }
    single {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        BlobDownloader(get(), { settings.serverBaseUrl }, { tokens.token }, ApiClient.defaultHttp())
    }
    // one player per screen (released when the detail closes or the screen goes)
    factory<MediaBrowsePort> { AppMediaBrowsePort(get(), get(), SentencePlayer(get(), get())) }
}

val mediaBrowseFeature = FeatureEntry(
    id = "media-browse",
    module = browseModule,
    routes = listOf(NavRoute(Routes.MEDIA, R.string.nav_media, topLevel = true) { MediaBrowseRoute() }),
)

@Composable
private fun MediaBrowseRoute() {
    val koin = getKoin()
    val vm: MediaBrowseViewModel = viewModel { MediaBrowseViewModel(koin.get<MediaBrowsePort>()) }
    val state by vm.state.collectAsState()
    MediaBrowseScreen(state, vm, captureEntry)
}
