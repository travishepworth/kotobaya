package kotobaya.android.shell.web

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The bridge's Android adapter (SHELL.md 2.1): `window.KotobayaShell` through
 * `WebViewCompat.addWebMessageListener`, restricted to the shell origin. Everything else is in [BridgeCore].
 * Never `addJavascriptInterface`.
 */
class BridgeTransport(val core: BridgeCore) : WebViewCompat.WebMessageListener {
    /** Registers the listener on [webView]; call before the first `loadUrl`. Requires [isSupported]. */
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView) {
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, setOf(ShellOrigin.ORIGIN), this)
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val text = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
        core.onMessage(text, sourceOrigin.toString(), isMainFrame) { replyProxy.postMessage(it) }
    }

    /** A main-frame page load started (from the WebViewClient). */
    fun onPageStarted() = core.onPageStarted()

    /** Sends a native message (A4/A6); see [BridgeCore.send]. */
    fun send(message: BridgeMessage): Boolean = core.send(message)

    companion object {
        const val OBJECT_NAME: String = "KotobayaShell"

        /** `WEB_MESSAGE_LISTENER` is available; else the shell shows its "update WebView" screen (2.1). */
        fun isSupported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
            .getOrDefault(false)
    }
}
