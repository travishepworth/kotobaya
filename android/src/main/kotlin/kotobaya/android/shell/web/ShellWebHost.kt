package kotobaya.android.shell.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Owns the shell's one WebView inside [container] (SHELL.md 1, 2.1, 6.3): settings, the client, the bridge
 * listener, the insets padding, the saved state and the last SPA path, and recreating the WebView after a
 * renderer crash. The [bridge] outlives WebView instances (its page lives are reset on every page start).
 */
class ShellWebHost(
    private val context: Context,
    private val container: FrameLayout,
    private val bridge: BridgeTransport,
    private val blobs: BlobIntercept,
) {
    var webView: WebView? = null
        private set

    /** The SPA path last shown (path + query), the launcher's start path (SHELL.md 6.1). */
    var lastPath: String = "/"
        private set

    init {
        // Pad for the system bars and the IME, so `100dvh` shrinks with the keyboard (SHELL.md 6.3).
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
    }

    /** Creates the WebView and loads the saved page, else [startPath] (sanitised by the path rule). */
    fun start(savedState: Bundle?, startPath: String?) {
        val saved = savedState?.getBundle(KEY_WEBVIEW)
        lastPath = NavPath.sanitize(savedState?.getString(KEY_PATH) ?: startPath)
        val view = createWebView()
        if (saved == null || view.restoreState(saved) == null) load(lastPath)
    }

    /** Loads [path] (SPA path rule applied) as a new page. */
    fun load(path: String) {
        val p = NavPath.sanitize(path)
        lastPath = p
        webView?.loadUrl(ShellOrigin.ORIGIN + p)
    }

    fun save(out: Bundle) {
        out.putString(KEY_PATH, lastPath)
        webView?.let { v -> out.putBundle(KEY_WEBVIEW, Bundle().also { v.saveState(it) }) }
    }

    fun onResume() = webView?.onResume() ?: Unit

    fun onPause() = webView?.onPause() ?: Unit

    fun destroy() {
        bridge.onPageStarted() // late `res` of this page are dropped
        webView?.let(::dispose)
        webView = null
    }

    private fun dispose(v: WebView) {
        container.removeView(v)
        v.stopLoading()
        v.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val v = WebView(context)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false // audio autoplay on reveal (6.3)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            setSupportMultipleWindows(false)
            // Safe browsing: platform default (on).
        }
        v.overScrollMode = View.OVER_SCROLL_NEVER
        v.webViewClient = ShellWebClient(
            context = context,
            bridge = bridge,
            blobs = blobs,
            onPath = { lastPath = it },
            onRendererGone = ::recreateAfterCrash,
        )
        bridge.attach(v) // before the first load: the listener applies to later page loads only
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = v
        return v
    }

    private fun recreateAfterCrash(dead: WebView) {
        if (webView !== dead) return
        dispose(dead)
        webView = null
        createWebView()
        Log.i(TAG, "WebView recreated after a renderer crash")
        load(lastPath)
    }

    companion object {
        private const val TAG = "KotobayaShell"
        private const val KEY_WEBVIEW = "shell.webview"
        private const val KEY_PATH = "shell.path"
    }
}
