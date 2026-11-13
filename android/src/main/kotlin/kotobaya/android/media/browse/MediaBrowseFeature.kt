package kotobaya.android.media.browse

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import kotobaya.android.di.FeatureEntry
import kotobaya.android.media.BlobDownloader
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
    // one player per screen (released when the detail closes or the screen goes)
    factory<MediaBrowsePort> { AppMediaBrowsePort(get(), get(), SentencePlayer(get(), get())) }
}

/**
 * Media browsing on the phone. The Media tab is gone (DESIGN §4.2, I-5: media import and the reader are web tasks), so
 * this entry only keeps the module (the struggle screen shares its cache types). Audio prefetch and storage live in
 * Settings › Audio and offline.
 */
val mediaBrowseFeature = FeatureEntry(
    id = "media-browse",
    module = browseModule,
)
