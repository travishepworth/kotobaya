package kotobaya.android.settings.ui

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertTrue
import kotobaya.android.MainActivity
import kotobaya.android.R
import kotobaya.android.app.KotobayaApp
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.di.featureEntries
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SettingsTabTest.TestApp::class, sdk = [34])
class SettingsTabTest {
    /** The real app, with WorkManager initialised (some settings sections schedule work when composed). */
    class TestApp : KotobayaApp() {
        override fun onCreate() {
            WorkManagerTestInitHelper.initializeTestWorkManager(this)
            super.onCreate()
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() = stopKoin()

    private fun str(id: Int) = rule.activity.getString(id)

    @Test
    fun unpairedHomeOffersPairingAndSettingsRendersEverySectionWithScanQr() {
        rule.onNodeWithText(str(R.string.home_pair_prompt)).assertExists()
        rule.onNodeWithText(str(R.string.home_pair_action)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.pair_scan_qr)).assertExists()
        // UI-APP: Today's Pair opens Settings on the Devices section; back shows the index with every section
        rule.onNodeWithContentDescription(str(R.string.settings_back)).performClick()
        rule.waitForIdle()
        val sections = FeatureRegistry(featureEntries).settingsSections
        assertTrue(sections.any { it.id == "devices" })
        for (s in sections) {
            val shown = rule.onAllNodes(hasText(str(s.titleRes))).fetchSemanticsNodes().size
            assertTrue(shown > 0, "missing settings section ${s.id}")
        }
    }
}
