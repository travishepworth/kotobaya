package kotobaya.android.demo

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

/**
 * UI-FOUND-APP: one Compose preview per screen of [DemoScreens] (the default demo scenario), on a phone, a foldable
 * and a tablet, light and dark, and at 200 % font size. The dataset comes from the debug assets, so previews render
 * in debug builds. [SCREEN_NAMES] must list every screen (DemoScreensTest checks it).
 */
class DemoScreenNames : PreviewParameterProvider<String> {
    override val values: Sequence<String> = SCREEN_NAMES.asSequence()
}

/** Every screen name of [DemoScreens.all] for the default scenario. */
val SCREEN_NAMES: List<String> = listOf(
    "home", "home-unpaired", "home-loading", "review-front", "review-back", "review-loading", "review-summary", "search-idle",
    "search-words", "search-cards", "word-detail", "note-detail", "decks", "deck-detail", "struggle", "struggle-word",
    "struggle-leeches", "struggle-confusables", "struggle-drill", "sync-flow", "sync-rejected", "settings", "settings-appearance",
    "settings-devices", "settings-dict", "settings-notify", "settings-push", "settings-media", "settings-takoboto",
    "settings-ankiconnect", "settings-sync", "settings-study", "settings-demo", "settings-about", "grammar-capture", "intake",
    "notify-permission",
)

@Composable
private fun DemoScreenPreview(name: String) {
    val assets = LocalContext.current.assets
    val screens = remember { DemoScreens.all(DemoData.load(assets)) }
    screens.first { it.name == name }.content()
}

@Preview(name = "phone", device = "spec:width=411dp,height=891dp")
@Preview(name = "phone dark", device = "spec:width=411dp,height=891dp", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "phone 200%", device = "spec:width=411dp,height=891dp", fontScale = 2f)
@Composable
fun PhonePreview(@PreviewParameter(DemoScreenNames::class) name: String) = DemoScreenPreview(name)

@Preview(name = "foldable", device = "spec:width=673dp,height=841dp")
@Preview(name = "foldable dark", device = "spec:width=673dp,height=841dp", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun FoldablePreview(@PreviewParameter(DemoScreenNames::class) name: String) = DemoScreenPreview(name)

@Preview(name = "tablet", device = "spec:width=1280dp,height=800dp,dpi=240")
@Preview(name = "tablet dark", device = "spec:width=1280dp,height=800dp,dpi=240", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TabletPreview(@PreviewParameter(DemoScreenNames::class) name: String) = DemoScreenPreview(name)
