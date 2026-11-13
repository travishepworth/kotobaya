package kotobaya.android.media.browse

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.media.BlobDownloader
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
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
}

val mediaBrowseFeature = FeatureEntry(
    id = "media-browse",
    module = browseModule,
    routes = listOf(NavRoute(Routes.MEDIA, R.string.nav_media, topLevel = true) { MediaBrowseScreen() }),
)
