package kotobaya.android.review.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File
import kotobaya.android.review.CardPage
import kotobaya.android.review.PageRequest
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.theme.KbTheme
import kotobaya.contracts.render.RenderedCard

/** The one reused WebView of the review screen: local assets only, no JS, every other URL blocked. */
@SuppressLint("SetJavaScriptEnabled")
internal class CardWebView(context: Context, private val mediaFile: (String) -> File?) {
    val view: WebView = WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.domStorageEnabled = false
        // transparent: the card surface behind it is the same colour as the card's own background
        setBackgroundColor(0)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                respond(context, request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
        }
    }

    private var shownHtml: String? = null

    fun show(html: String) {
        if (html == shownHtml) return
        shownHtml = html
        view.loadDataWithBaseURL(CardPage.BASE_URL, html, "text/html", "utf-8", null)
    }

    private fun respond(context: Context, url: String): WebResourceResponse = when (val r = CardPage.decide(url)) {
        is PageRequest.Asset -> try {
            WebResourceResponse(mimeOf(r.path), null, context.assets.open(r.path))
        } catch (e: java.io.IOException) {
            blocked(404)
        }
        is PageRequest.Media -> mediaFile(r.sha256)?.takeIf { it.isFile }?.let {
            WebResourceResponse("application/octet-stream", null, it.inputStream())
        } ?: blocked(404)
        PageRequest.Blocked -> blocked(403)
    }

    private fun blocked(status: Int) =
        WebResourceResponse("text/plain", "utf-8", status, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}

/**
 * The card page for [card] in the app's theme: the WebView's `prefers-color-scheme` follows the system, not the
 * in-app override, so the page's `<html>` carries `data-theme` (card.css honours it; DESIGN §2.5). The page body is
 * transparent so the card surface shows through without a seam.
 */
internal fun themedPage(card: RenderedCard, dark: Boolean): String =
    CardPage.build(card).replaceFirst(
        "<html>",
        "<html data-theme=\"${if (dark) "dark" else "light"}\"><style>html,body{margin:0;background:transparent}" +
            "html body .kb-card{background:transparent}</style>",
    )

/**
 * How the review card's HTML is drawn. Production uses the WebView ([CardWebViewContent]); the screenshot harness
 * provides [CardStandIn], because Robolectric does not draw WebViews.
 */
val LocalCardContent = staticCompositionLocalOf<(@Composable (card: RenderedCard, mediaFile: (String) -> File?, modifier: Modifier) -> Unit)?> { null }

/**
 * How the immersive review screen is left (the shell supplies "back to where you came from"); null hides the close
 * button (a deck's study page has its own bar).
 */
val LocalReviewExit = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Draws [card] with the provided [LocalCardContent] or the WebView. */
@Composable
internal fun CardContent(card: RenderedCard, mediaFile: (String) -> File?, modifier: Modifier) {
    val override = LocalCardContent.current
    if (override != null) override(card, mediaFile, modifier) else CardWebViewContent(card, mediaFile, modifier)
}

@Composable
private fun CardWebViewContent(card: RenderedCard, mediaFile: (String) -> File?, modifier: Modifier) {
    val context = LocalContext.current
    val web = remember { CardWebView(context, mediaFile) }
    DisposableEffect(web) { onDispose { web.view.destroy() } }
    val page = themedPage(card, KbTheme.isDark)
    AndroidView(factory = { web.view }, update = { web.show(page) }, modifier = modifier)
}

/**
 * Screenshot stand-in for the card WebView: the card's text blocks in the card's type roles (first block = headword,
 * `<hr>` = divider), centred like card.css. Not used by the app.
 */
@Composable
fun CardStandIn(card: RenderedCard, modifier: Modifier = Modifier) {
    val c = KbTheme.colors
    val blocks = remember(card.html) { standInBlocks(card.html) }
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var first = true
            for (b in blocks) {
                if (b == DIVIDER) {
                    KbDivider(Modifier.padding(vertical = 8.dp))
                    continue
                }
                val style = if (first) KbTheme.type.jpDisplay else if (b.any { it.code > 0x2E80 }) KbTheme.type.jpBody else KbTheme.type.bodyLg
                Text(b, style = style, color = if (first) c.text else c.text, textAlign = TextAlign.Center)
                first = false
            }
        }
    }
}

private const val DIVIDER = "\u0000hr"

private fun standInBlocks(html: String): List<String> {
    val body = html
        .replace(Regex("<rt>.*?</rt>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<hr[^>]*>", RegexOption.IGNORE_CASE), "\n$DIVIDER\n")
        .replace(Regex("<br\\s*/?>|</(div|p|li|h\\d|section)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    return body.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
