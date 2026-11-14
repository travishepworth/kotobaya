package kotobaya.android.struggle

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.grammar.captureEntry
import kotobaya.android.struggle.ui.StruggleScreen
import org.koin.compose.getKoin
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.media.browse.FileBrowseCache
import kotobaya.android.nav.NavRoute
import kotobaya.android.net.ApiClient
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.dsl.module

const val STRUGGLE_ROUTE = "struggle"

/** `kotobaya://struggle/word/<entrySeq>` (the hot-word widget's link): the entry to open on the struggle screen. */
object StruggleDeepLink {
    private val _pending = MutableStateFlow<Long?>(null)
    val pending: StateFlow<Long?> get() = _pending

    /** Returns true when [intent] was a struggle link. */
    fun handle(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "kotobaya" || uri.host != "struggle") return false
        val seq = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return false
        _pending.value = seq
        return true
    }

    fun consume() {
        _pending.value = null
    }
}

private val struggleModule = module {
    single<StruggleSource> {
        val settings = get<SettingsStore>()
        val tokens = get<TokenStore>()
        StruggleRepository(
            ApiClient({ settings.serverBaseUrl }, { tokens.token }),
            FileBrowseCache(lazy { File(get<Context>().filesDir, "struggle") }),
            { settings.serverBaseUrl },
            { tokens.token },
        )
    }
}

val struggleFeature = FeatureEntry(
    id = "struggle",
    module = struggleModule,
    routes = listOf(NavRoute(STRUGGLE_ROUTE, R.string.nav_struggle, topLevel = true) { StruggleRoute() }),
)

@Composable
private fun StruggleRoute() {
    val koin = getKoin()
    val vm: StruggleViewModel = viewModel {
        StruggleViewModel(koin.get<StruggleSource>(), StruggleDeepLink.pending, StruggleDeepLink::consume)
    }
    val state by vm.state.collectAsState()
    StruggleScreen(state, vm, captureEntry)
}
