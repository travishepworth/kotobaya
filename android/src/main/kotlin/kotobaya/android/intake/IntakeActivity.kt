package kotobaya.android.intake

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotobaya.android.MainActivity
import kotobaya.android.shell.nav.ShellLinks

/**
 * SHELL-A4 (ADR-036): a no-UI trampoline for the share sheet (`ACTION_SEND text/plain`), the text-selection menu
 * (`ACTION_PROCESS_TEXT`) and `kotobaya://add`: parse with [IntakeParser], start [MainActivity] on the SPA path, finish.
 */
class IntakeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = ShellLinks.pathFor(intent)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(ShellLinks.EXTRA_PATH, path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        fun parse(intent: Intent?): IntakeTarget = IntakeParser.parse(
            action = intent?.action,
            mimeType = intent?.type,
            extraText = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            processText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(),
            data = intent?.dataString,
        )
    }
}
