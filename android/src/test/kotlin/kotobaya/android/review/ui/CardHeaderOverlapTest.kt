package kotobaya.android.review.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import kotobaya.android.R
import kotobaya.android.ui.theme.KotobayaTheme
import kotobaya.android.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** KIND-CHIP-OVERLAP: the tag row (New, kind chip, audio) never intersects the card content below it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CardHeaderOverlapTest {
    @get:Rule
    val rule = createComposeRule()

    private fun check(widthDp: Int, fontScale: Float, isNew: Boolean, audio: Boolean) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                KotobayaTheme(ThemeMode.Light) {
                    Column(Modifier.width(widthDp.dp)) {
                        CardHeaderRow(isNew, R.string.review_kind_vocab, audio, audioMissing = audio, onPlayAudio = {})
                        Box(Modifier.weight(1f).fillMaxWidth().testTag(TAG_CARD_CONTENT))
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
    }

    @Test fun narrowNewAudio() = check(320, 1.3f, isNew = true, audio = true)

    @Test fun regularNewAudio() = check(411, 1.0f, isNew = true, audio = true)

    @Test fun kindOnly() = check(360, 1.3f, isNew = false, audio = false)
}
