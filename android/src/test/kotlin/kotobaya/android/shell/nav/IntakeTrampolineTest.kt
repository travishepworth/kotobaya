package kotobaya.android.shell.nav

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotobaya.android.MainActivity
import kotobaya.android.intake.IntakeActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SHELL-A4 acceptance: the intake activity is a no-UI trampoline into MainActivity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class IntakeTrampolineTest {
    private fun run(intent: Intent): Pair<Intent?, Boolean> {
        intent.setClass(ApplicationProvider.getApplicationContext(), IntakeActivity::class.java)
        val controller = Robolectric.buildActivity(IntakeActivity::class.java, intent).create()
        val started = shadowOf(controller.get()).nextStartedActivity
        return started to controller.get().isFinishing
    }

    @Test
    fun sendTextStartsMainActivityOnSearchAndFinishes() {
        val (started, finished) = run(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "猫"))
        assertEquals(MainActivity::class.java.name, started?.component?.className)
        assertEquals("/search?q=%E7%8C%AB", started?.getStringExtra(ShellLinks.EXTRA_PATH))
        assertEquals("/search?q=%E7%8C%AB", ShellLinks.target(started))
        assertTrue(finished)
    }

    @Test
    fun badInputStillOpensHome() {
        val (started, finished) = run(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "  "))
        assertEquals("/", started?.getStringExtra(ShellLinks.EXTRA_PATH))
        assertTrue(finished)
    }
}
