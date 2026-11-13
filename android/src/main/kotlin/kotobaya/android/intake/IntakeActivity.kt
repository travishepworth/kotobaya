package kotobaya.android.intake

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotobaya.android.intake.ui.IntakeSheet
import kotobaya.android.nav.Appearance
import kotobaya.android.ui.theme.KotobayaTheme

/**
 * Entry for the share sheet (`ACTION_SEND text/plain`), the text-selection menu (`ACTION_PROCESS_TEXT`) and
 * the `kotobaya://add` / `kotobaya://word/<id>` links. Parsing is [IntakeParser]; creation is T305b's
 * `NoteCreator` behind [IntakeServices].
 */
class IntakeActivity : ComponentActivity() {
    internal lateinit var model: IntakeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = parse(intent)
        model = ViewModelProvider(this, viewModelFactory { initializer { IntakeViewModel(IntakeServicesProvider.get(), target) } })[IntakeViewModel::class.java]
        setContent {
            val state by model.state.collectAsState()
            KotobayaTheme(Appearance.load(this)) { IntakeSheet(state, model, onClose = { finish() }) }
        }
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
