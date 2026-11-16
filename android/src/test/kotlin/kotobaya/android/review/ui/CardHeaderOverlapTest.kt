package kotobaya.android.review.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlin.test.assertTrue
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.KotobayaTheme
import kotobaya.android.ui.theme.ThemeMode
import kotobaya.contracts.render.RenderedCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * KIND-CHIP-OVERLAP: the REAL card face ([CardFace], the composable ReviewCard uses) never lets the tag row (New,
 * kind chip, audio) intersect the card content. The WebView is swapped for a placeholder via [LocalCardContent];
 * the layout code path is the production one. Also writes PNGs of the face for a visual check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class CardHeaderOverlapTest {
    @get:Rule
    val rule = createComposeRule()

    private fun card(audio: Boolean) =
        RenderedCard("<div>x</div>", if (audio) listOf("a".repeat(64)) else emptyList(), "", emptyList())

    private fun check(widthDp: Int, fontScale: Float, isNew: Boolean, audio: Boolean, png: String? = null) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalCardContent provides { _, _, m ->
                    Box(m.background(Color(0xFFFFF3C4)), contentAlignment = Alignment.TopCenter) {
                        Text("Content top edge: headword starts here", color = Color.Black)
                    }
                },
            ) {
                KotobayaTheme(ThemeMode.Light) {
                    Box(Modifier.width(widthDp.dp).height(420.dp).background(KbTheme.colors.surface)) {
                        CardFace(
                            rendered = card(audio), isNew = isNew, noteType = "vocab", audioMissing = audio,
                            onPlayAudio = {}, mediaFile = { null }, contentAlpha = { 1f }, backPanel = null,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val chip = rule.onNodeWithTag(TAG_KIND_CHIP, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val header = rule.onNodeWithTag(TAG_CARD_HEADER, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val content = rule.onNodeWithTag(TAG_CARD_CONTENT, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue((chip.bottom - chip.top).value > 0f, "chip has size")
        assertTrue(chip.bottom <= content.top, "chip $chip must end above content $content ($widthDp dp, x$fontScale)")
        assertTrue(header.bottom <= content.top, "header $header must end above content $content")
        if (png != null) {
            val dir = File(PNG_DIR)
            dir.mkdirs()
            System.setProperty("roborazzi.test.record", "true") // always write these PNGs, regardless of -Pscreenshots
            rule.onRoot().captureRoboImage(File(dir, png))
        }
    }

    @Test fun narrowNewAudio() = check(320, 1.3f, isNew = true, audio = true)

    @Test fun regularNewAudio() = check(411, 1.0f, isNew = true, audio = true)

    @Test fun kindOnly() = check(360, 1.3f, isNew = false, audio = false)

    @Test fun pngNewKindAudio() = check(360, 1.3f, isNew = true, audio = true, png = "new-vocab-audio-360dp-x1.3.png")

    @Test fun pngKindOnly() = check(360, 1.3f, isNew = false, audio = false, png = "vocab-only-360dp-x1.3.png")

    private companion object {
        const val PNG_DIR = "/tmp/claude-1000/-home-travmonkey-repos-kotobaya/05f3536e-dc73-4935-83df-766bf71b9d1a/scratchpad/and-chip"
    }
}
