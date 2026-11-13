package kotobaya.android.grammar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotobaya.android.di.FeatureEntry
import kotobaya.android.grammar.ui.CaptureEntrySlot
import kotobaya.android.grammar.ui.GrammarCaptureEntry
import kotobaya.android.pairing.TokenStore
import kotobaya.android.settings.SettingsStore
import org.koin.compose.getKoin
import org.koin.dsl.module

/** The capture panel's server calls (no route of its own: sentence views embed [captureEntry]). */
val grammarCaptureFeature = FeatureEntry(
    id = "grammar-capture",
    module = module {
        factory<GrammarCapturePort> {
            val settings = get<SettingsStore>()
            val tokens = get<TokenStore>()
            GrammarCaptureRepository({ settings.serverBaseUrl }, { tokens.token })
        }
    },
)

/** One sentence's capture entry, bound to its ViewModel (keyed by sentence in the host screen's back-stack entry). */
@Composable
fun GrammarCaptureRoute(sentenceId: String, mediaId: String?, text: String) {
    val koin = getKoin()
    val vm: GrammarCaptureViewModel = viewModel(key = "grammar-capture-$sentenceId") {
        GrammarCaptureViewModel(sentenceId, mediaId, text, koin.get<GrammarCapturePort>())
    }
    val state by vm.state.collectAsState()
    GrammarCaptureEntry(text, state, vm)
}

/** The slot sentence views take. */
val captureEntry: CaptureEntrySlot = { sentenceId, mediaId, text -> GrammarCaptureRoute(sentenceId, mediaId, text) }
