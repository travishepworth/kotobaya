package kotobaya.android.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.test.assertEquals
import kotobaya.android.MainActivity
import kotobaya.android.R
import kotobaya.android.db.DbThreadGuard
import kotobaya.android.di.FeatureRegistry
import kotobaya.android.di.featureEntries
import kotobaya.android.nav.Tab
import kotobaya.android.nav.allRoutes
import kotobaya.android.nav.navTag
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-8 (I-4, I-7): the app starts and every main screen (each bottom-bar tab, the Settings screen
 * with every feature's section, the Home sync chip) renders without one database statement on the
 * main thread. StrictMode cannot see SQLite under Robolectric, so the app driver's [DbThreadGuard]
 * (the same check, on every statement) is the detector; any main-thread statement fails the test
 * with its SQL and stack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = NoDbOnMainThreadTest.TestApp::class, sdk = [34])
class NoDbOnMainThreadTest {
    /** The real app, with WorkManager initialised (some sections schedule work when composed). */
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

    private fun settle() {
        rule.waitForIdle()
        // let IO work posted by the screens finish and its results recompose
        Thread.sleep(300)
        rule.waitForIdle()
    }

    @Test
    fun noScreenTouchesTheDatabaseOnTheMainThread() {
        val guard = GlobalContext.get().get<DbThreadGuard>()
        settle()
        val tabs = allRoutes(FeatureRegistry(featureEntries)).filter { it.topLevel }
        assertEquals(Tab.entries.map { it.route }, tabs.map { it.route })
        for (tab in Tab.entries) {
            rule.onNodeWithTag(navTag(tab)).performClick()
            settle()
            // Review is immersive on phones (no bottom bar): leave it through its close button
            if (tab == Tab.REVIEW) {
                rule.onNodeWithContentDescription(rule.activity.getString(R.string.review_close)).performClick()
                settle()
            }
        }
        // Today again (its chip reads the sync status) and Settings last (every feature's section)
        rule.onNodeWithTag(navTag(Tab.TODAY)).performClick()
        settle()
        rule.onNodeWithTag(navTag(Tab.SETTINGS)).performClick()
        settle()
        assertEquals(emptyList(), guard.violations, guard.violations.joinToString("\n\n"))
    }
}
