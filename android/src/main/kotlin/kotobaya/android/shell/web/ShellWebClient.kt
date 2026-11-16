package kotobaya.android.shell.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.IOException

/** Serves `assets/web/` for every path of the shell origin, with the SPA fallback of [AssetPaths]. */
class ShellAssetHandler(private val assets: AssetManager) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val asset = AssetPaths.resolve("/$path") ?: return null
        val stream = try {
            assets.open(asset)
        } catch (e: IOException) {
            return notFound()
        }
        val mime = AssetPaths.mimeType(asset)
        return WebResourceResponse(mime, AssetPaths.encoding(mime), stream).apply {
            if (asset == AssetPaths.INDEX) responseHeaders = mapOf("Cache-Control" to "no-cache")
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), "".byteInputStream())
}

fun InterceptAnswer.toWebResponse(): WebResourceResponse =
    WebResourceResponse(mimeType, encoding, status, reason, headers, body)

/**
 * The shell WebView's client: the blob intercept, then the bundled assets (SHELL.md 2.7, 6.1); other origins
 * open in the system browser (2.1); page starts open a new bridge page life (2.5); the visited path is
 * remembered for the launcher (6.1); a renderer crash is reported to [onRendererGone] (the host recreates
 * the WebView).
 */
class ShellWebClient(
    private val context: Context,
    private val bridge: BridgeTransport,
    private val blobs: Lazy<BlobIntercept>,
    private val onPath: (String) -> Unit,
    private val onRendererGone: (WebView) -> Unit,
) : WebViewClientCompat() {
    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ShellOrigin.HOST)
        .setHttpAllowed(false)
        .addPathHandler("/", ShellAssetHandler(context.assets))
        .build()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" || url.host != ShellOrigin.HOST) return null
        val path = url.path ?: "/"
        blobs.value.answer(request.method ?: "GET", path)?.let { return it.toWebResponse() }
        return assetLoader.shouldInterceptRequest(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme == "https" && url.host == ShellOrigin.HOST) return false
        openExternally(url)
        return true
    }

    private fun openExternally(url: Uri) {
        if (url.scheme != "https" && url.scheme != "http" && url.scheme != "mailto") return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no app for an external link")
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        bridge.onPageStarted()
        NavPath.ofUrl(url)?.let(onPath)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        NavPath.ofUrl(url)?.let(onPath)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.w(TAG, "renderer gone (crash=${detail.didCrash()}); recreating the WebView")
        bridge.onPageStarted()
        onRendererGone(view)
        return true
    }

    private companion object {
        const val TAG = "KotobayaShell"
    }
}
