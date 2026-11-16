package kotobaya.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import java.util.UUID
import kotobaya.android.shell.nav.BackProtocol
import kotobaya.android.shell.nav.BackTimer
import kotobaya.android.shell.nav.Haptics
import kotobaya.android.shell.nav.ShellLinks
import kotobaya.android.shell.nav.SystemBars
import kotobaya.android.shell.web.BridgeMessage
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

    private var container: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val backCallback: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = back.onBackPressed()
    }
    private val back: BackProtocol = BackProtocol(
        sendBack = { id -> bridge?.send(BridgeMessage.Back(id)) ?: false },
        timer = BackTimer { ms, block ->
            val r = Runnable(block)
            handler.postDelayed(r, ms)
            ({ handler.removeCallbacks(r) })
        },
        fallback = {
            val v = host?.webView
            if (v != null && v.canGoBack()) v.goBack() else moveTaskToBack(true)
        },
        moveToBack = { moveTaskToBack(true) },
        newId = { UUID.randomUUID().toString() },
        onEnabledChanged = { backCallback.isEnabled = it },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION") // Below API 30 the IME insets only reach the views with adjustResize.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (!BridgeTransport.isSupported()) {
            setContentView(unsupportedScreen())
            return
        }
        kotobaya.android.shell.api.device.NotificationPermissionLauncher.register(this) // SHELL-A7
        val container = FrameLayout(this)
        this.container = container
        setContentView(container)
        onBackPressedDispatcher.addCallback(this, backCallback)
        // Only the Koin handle is taken here; the API, the stores and the DB driver are resolved on IO when used.
        val koin = getKoin()
        val transport = ShellWiring.bridge(koin, onWebMessage = ::onWebMessage, onPageReset = { back.onPageStarted(); SystemBars.reset(window, container) })
        bridge = transport
        // A restored instance keeps its page; a fresh launch goes to the path of its intent (SHELL.md 6.1).
        val start = if (savedInstanceState == null) ShellLinks.target(intent) else null
        host = ShellWebHost(this, container, transport, ShellWiring.blobs(koin)).also { it.start(savedInstanceState, start) }
    }

    /** Widget, tile, notification, deep link or intake trampoline while the activity lives (SHELL.md 6.1 "Delivery"). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = ShellLinks.target(intent) ?: return
        // `ready` seen → posted now; page still loading → kept and sent right after `hello` (BridgeCore.send).
        bridge?.send(BridgeMessage.Navigate(path))
    }

    private fun onWebMessage(m: BridgeMessage) {
        when (m) {
            is BridgeMessage.BackState -> back.onBackState(m.canHandle)
            is BridgeMessage.BackResult -> back.onBackResult(m.id, m.handled)
            is BridgeMessage.Haptic -> window.decorView.let { Haptics.perform(it, m.kind) }
            is BridgeMessage.Theme -> container?.let { SystemBars.apply(window, it, m.dark, m.surface) }
            else -> Unit
        }
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
        handler.removeCallbacksAndMessages(null)
        host?.destroy()
        host = null
        super.onDestroy()
    }
}
