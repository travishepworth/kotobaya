package kotobaya.android.shell.web

import android.util.Log
import app.cash.sqldelight.db.SqlDriver
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotobaya.android.media.BlobDownloader
import kotobaya.android.shell.ShellEventHub
import kotobaya.android.shell.api.device.DictEvents
import kotobaya.android.shell.events.ShellEvents
import kotobaya.android.media.BlobStore
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import kotobaya.android.shell.api.LocalApi
import kotobaya.android.sync.NetworkMonitor
import kotobaya.android.sync.SqlDelightDeviceStore
import kotobaya.contracts.sync.JsonNumber
import kotobaya.contracts.sync.JsonObject
import kotobaya.contracts.sync.JsonString
import kotobaya.sync.client.store.UuidV7Source
import kotobaya.sync.client.store.bookkeeping
import org.koin.core.Koin
import org.koin.core.qualifier.named

private const val TAG = "KotobayaShell"
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"

/**
 * Builds the bridge and the blob intercept from the app's Koin graph. Requests are never cancelled by the
 * activity (SHELL.md 2.5 "Reload"): they run on a scope of their own, and a late `res` is dropped by page life.
 */
object ShellWiring {
    /** The one process-wide scope of shell requests (declared in `shellFeature`). */
    val SCOPE: org.koin.core.qualifier.Qualifier = named("shellScope")

    /** Nothing but the scope is resolved here: the API, the store and the DB driver are resolved on IO, when used. */
    fun bridge(
        koin: Koin,
        onWebMessage: (BridgeMessage) -> Unit = {},
        onPageReset: () -> Unit = {},
    ): BridgeTransport {
        val core = BridgeCore(
            scope = koin.get<CoroutineScope>(SCOPE),
            io = Dispatchers.IO,
            main = Dispatchers.Main.immediate,
            handle = { koin.get<LocalApi>().handle(it) },
            helloData = {
                val (seq, epoch) = koin.get<SqlDelightDeviceStore>().read { it.bookkeeping.pullSeq to it.bookkeeping.logEpoch }
                JsonObject(
                    linkedMapOf(
                        "high_water_seq" to JsonNumber(seq.toDouble()),
                        "log_epoch" to JsonString(epoch ?: NIL_UUID),
                        "server_time" to JsonString(BridgeCore.formatAt(System.currentTimeMillis())),
                    ),
                )
            },
            onWebMessage = onWebMessage,
            onIncompatible = { Log.w(TAG, "bundle wants local API ${it.minLocalApi} > ${LocalApi.LOCAL_API_VERSION}") },
            warn = { Log.w(TAG, it) },
            onPageReset = onPageReset,
        )
        val transport = BridgeTransport(core)
        ShellEventHub.transport = transport
        // the event producers are built on IO, once per process (they are idle without a page)
        koin.get<CoroutineScope>(SCOPE).launch(Dispatchers.IO) {
            runCatching { koin.get<ShellEvents>() }.onFailure { Log.w(TAG, "shell events: $it") }
            runCatching { koin.get<DictEvents>() }.onFailure { Log.w(TAG, "shell dict events: $it") }
        }
        return transport
    }

    /** Built on first use (a WebView background thread), not on the main thread. */
    fun blobs(koin: Koin): Lazy<BlobIntercept> = lazy { buildBlobs(koin) }

    private fun buildBlobs(koin: Koin): BlobIntercept {
        val store = koin.get<BlobStore>()
        val tokens = koin.get<TokenStore>()
        val settings = koin.get<SettingsStore>()
        val network = koin.get<NetworkMonitor>()
        val downloader = BlobDownloader(store, { settings.serverBaseUrl }, { tokens.token }, ApiClient.defaultHttp())
        val ids = UuidV7Source(SecureRandom().asKotlinRandom())
        return BlobIntercept(
            store = store,
            contentTypes = BlobContentTypes.over(koin.get<SqlDriver>(named("syncAppDriver"))),
            isPaired = { tokens.token != null },
            isOnline = network::isOnline,
            download = { hash -> downloader.download(hash, Long.MAX_VALUE) },
            newRequestId = { ids.newOpId(System.currentTimeMillis()) },
        )
    }
}
