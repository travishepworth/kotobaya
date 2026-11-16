package kotobaya.android.media.browse

import kotobaya.android.di.FeatureEntry
import kotobaya.android.media.BlobDownloader
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.dsl.module

private val browseModule = module {
    single {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        BlobDownloader(get(), { settings.serverBaseUrl }, { tokens.token }, ApiClient.defaultHttp())
    }
}

/** Provides the blob downloader the shell and the prefetcher share (the browse screens went with the Compose UI). */
val mediaBrowseFeature = FeatureEntry(
    id = "media-browse",
    module = browseModule,
)
