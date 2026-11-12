package kotobaya.spike.ankistandin

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shows the call log; also logs any intent (CREATE_FLASHCARD / SEND) a dictionary fires at us. */
class LogActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        scroll = ScrollView(this).apply { addView(text) }
        val clear = Button(this).apply {
            setText(R.string.button_clear)
            setOnClickListener { CallLog.clear() }
        }
        val share = Button(this).apply {
            setText(R.string.button_share)
            setOnClickListener { shareLog() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(clear)
            addView(share)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
                addView(buttons)
                addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        logIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        CallLog.setListener(::render)
        render()
    }

    override fun onPause() {
        CallLog.setListener(null)
        super.onPause()
    }

    private fun logIntent(intent: Intent?) {
        if (intent == null || intent.action == Intent.ACTION_MAIN) return
        CallLog.log(
            "intent caller=${referrer} action=${intent.action} type=${intent.type} data=${intent.data} " +
                "extras=${CallLog.show(intent.extras)}",
        )
    }

    private fun render() {
        val lines = CallLog.snapshot()
        text.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, CallLog.snapshot().joinToString("\n"))
        }
        startActivity(Intent.createChooser(send, getString(R.string.button_share)))
    }
}
