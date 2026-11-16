package kotobaya.android

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import kotobaya.android.shell.web.BridgeTransport
import kotobaya.android.shell.web.ShellWebHost
import kotobaya.android.shell.web.ShellWiring
import org.koin.android.ext.android.getKoin

/**
 * SHELL-A3 (ADR-036): hosts the SPA in one WebView loaded from `https://appassets.androidplatform.net/`.
 * The Compose app lives on in [kotobaya.android.legacy.LegacyActivity] ("Kotobaya (old)") until SHELL-Z1.
 * Widget, tile and notification intents keep targeting this activity (navigation: SHELL-A4).
 */
class MainActivity : ComponentActivity() {
    private var host: ShellWebHost? = null

    /** The bridge of this activity, for native → SPA messages (A4/A6); null on the "update WebView" screen. */
    var bridge: BridgeTransport? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") // Below API 30 the IME insets only reach the views with adjustResize.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!BridgeTransport.isSupported()) {
            setContentView(unsupportedScreen())
            return
        }
        val container = FrameLayout(this)
        setContentView(container)
        val koin = getKoin()
        val transport = ShellWiring.bridge(koin)
        bridge = transport
        host = ShellWebHost(this, container, transport, ShellWiring.blobs(koin)).also { it.start(savedInstanceState, null) }
    }

    private fun unsupportedScreen(): TextView = TextView(this).apply {
        setText(R.string.shell_webview_unsupported)
        gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        textSize = 18f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        host?.save(outState)
    }

    override fun onResume() {
        super.onResume()
        host?.onResume()
    }

    override fun onPause() {
        host?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        host?.destroy()
        host = null
        super.onDestroy()
    }
}
