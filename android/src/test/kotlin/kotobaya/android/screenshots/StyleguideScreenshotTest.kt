package kotobaya.android.screenshots

import android.app.Application
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotobaya.android.styleguide.StyleguidePage
import kotobaya.android.styleguide.StyleguidePageFrame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-DS-APP: the debug styleguide in the UI-FOUND-APP matrix (phone / foldable / tablet x light / dark x font 1.0 /
 * 2.0), one image per page: `android/screenshots/styleguide/<page>/<device>-<theme>-<font>.png`. Runs only under
 * `make screenshots-app`. The theme follows the system (the `night` qualifier), as in the app's default mode.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class StyleguideScreenshotTest(
    private val device: ScreenshotMatrixTest.Device,
    private val dark: Boolean,
    private val fontScale: Float,
) {
    @Test
    fun captureEveryStyleguidePage() {
        assumeTrue("screenshots run with -Pscreenshots only", System.getProperty("kotobaya.screenshots") == "true")
        val out = File(checkNotNull(System.getProperty("kotobaya.screenshots.dir")), "styleguide")
        RuntimeEnvironment.setQualifiers("${device.size}-${if (dark) "night" else "notnight"}-${device.density}")
        RuntimeEnvironment.setFontScale(fontScale)
        val file = "${device.name}-${if (dark) "dark" else "light"}-${if (fontScale == 1f) "font100" else "font200"}.png"
        for (page in StyleguidePage.entries) {
            captureRoboImage(File(out, "${page.id}/$file")) { StyleguidePageFrame(page) }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}-font={2}")
        fun matrix(): List<Array<Any>> = ScreenshotMatrixTest.matrix()
    }
}
