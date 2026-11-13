package kotobaya.android.screenshots

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotobaya.android.demo.DemoData
import kotobaya.android.demo.DemoScreens
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-FOUND-APP: the Android screenshot matrix (the design feedback loop, not a gate). Every screen of
 * [DemoScreens] x phone / foldable / tablet x light / dark x font scale 1.0 / 2.0, for the default demo scenario,
 * plus the screens that have an empty state for the `empty` scenario. Written to
 * `android/screenshots/<scenario>/<screen>/<device>-<theme>-<font>.png` by Roborazzi.
 *
 * Runs only under `make screenshots-app` (gradle `-Pscreenshots`); `make check` skips it. The review card itself is
 * a WebView, which Robolectric does not draw: the review shots show the screen around it.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ScreenshotMatrixTest(private val device: Device, private val dark: Boolean, private val fontScale: Float) {
    /** A window size class to render at (Robolectric resource qualifiers). */
    data class Device(val name: String, val size: String, val density: String) {
        override fun toString() = name
    }

    @Test
    fun captureEveryScreen() {
        assumeTrue("screenshots run with -Pscreenshots only", System.getProperty("kotobaya.screenshots") == "true")
        val out = File(checkNotNull(System.getProperty("kotobaya.screenshots.dir")))
        RuntimeEnvironment.setQualifiers("${device.size}-${if (dark) "night" else "notnight"}-${device.density}")
        RuntimeEnvironment.setFontScale(fontScale)
        val assets = ApplicationProvider.getApplicationContext<Application>().assets
        val file = "${device.name}-${if (dark) "dark" else "light"}-${if (fontScale == 1f) "font100" else "font200"}.png"
        for (scenario in SCENARIOS) {
            val screens = DemoScreens.all(DemoData.load(assets, scenario))
                .filter { scenario == "default" || it.name in EMPTY_STATE_SCREENS }
            for (s in screens) captureRoboImage(File(out, "$scenario/${s.name}/$file")) { s.content() }
        }
    }

    companion object {
        private val SCENARIOS = listOf("default", "empty")

        /** Screens whose empty state the `empty` scenario shows. */
        private val EMPTY_STATE_SCREENS = setOf(
            "home", "review-front", "search-cards", "decks", "media", "struggle", "struggle-leeches", "struggle-confusables",
            "struggle-drill",
        )

        private val DEVICES = listOf(
            Device("phone", "w411dp-h891dp-port", "xxhdpi"),
            Device("foldable", "w673dp-h841dp-port", "xhdpi"),
            Device("tablet", "w1280dp-h800dp-land", "hdpi"),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}-font={2}")
        fun matrix(): List<Array<Any>> = DEVICES.flatMap { d ->
            listOf(false, true).flatMap { dark -> listOf(1f, 2f).map { f -> arrayOf<Any>(d, dark, f) } }
        }
    }
}
