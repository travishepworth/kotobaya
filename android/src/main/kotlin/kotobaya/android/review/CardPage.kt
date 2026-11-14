package kotobaya.android.review

import kotobaya.contracts.render.RenderedCard
import java.net.URI

/** What the card WebView may fetch. Everything else is blocked: the card view makes no network request. */
sealed interface PageRequest {
    /** A file of `assets/card/`, as an Android asset path (`card/card.css`). */
    data class Asset(val path: String) : PageRequest

    /** A cached blob (sha256 hex), served from local storage only. */
    data class Media(val sha256: String) : PageRequest

    /**
     * The card document itself: the main-frame navigation `loadDataWithBaseURL` starts. WebView reports it to
     * `shouldInterceptRequest` as the bare `data:text/html;charset=utf-8;base64,` URL (the HTML travels separately),
     * so it must be left to WebView (`null`). Answering it with a blocked response is AND-CARDHTML: Chromium then
     * shows its "ERR_HTTP_RESPONSE_CODE_FAILURE" page instead of the card.
     */
    data object Document : PageRequest

    data object Blocked : PageRequest
}

/** The HTML page of one rendered card side and the request policy of the WebView that shows it. */
object CardPage {
    /** Virtual origin the page is loaded under (the same convention as WebViewAssetLoader). */
    const val HOST = "appassets.androidplatform.net"
    const val BASE_URL = "https://$HOST/"
    const val MEDIA_URL_TEMPLATE = "https://$HOST/media/{sha256}"
    private const val CARD_ASSETS = "/assets/card/"
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** The full document for [card]: shared card CSS from local assets, then the note type's CSS. */
    fun build(card: RenderedCard): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src https://$HOST 'unsafe-inline'; " +
            "img-src https://$HOST; media-src https://$HOST; font-src https://$HOST\">" +
            "<link rel=\"stylesheet\" href=\"${BASE_URL}assets/card/card.css\">" +
            (if (card.extraCss.isBlank()) "" else "<style>${card.extraCss.replace("</", "<\\/")}</style>") +
            "</head><body>${card.html}</body></html>"

    /**
     * [build] in the app's theme: the WebView's `prefers-color-scheme` follows the system, not the in-app override,
     * so the page's `<html>` carries `data-theme` (card.css honours it; DESIGN §2.5). The page body is transparent
     * so the card surface shows through without a seam.
     */
    fun themed(card: RenderedCard, dark: Boolean): String =
        build(card).replaceFirst(
            "<html>",
            "<html data-theme=\"${if (dark) "dark" else "light"}\"><style>html,body{margin:0;background:transparent}" +
                "html body .kb-card{background:transparent}</style>",
        )

    /**
     * Decides one request of the WebView: the main-frame `data:` document of `loadDataWithBaseURL`
     * ([PageRequest.Document]), `https://HOST/assets/card/...` and `https://HOST/media/<sha256>` pass; everything
     * else is blocked (a sub-resource `data:` URL too).
     */
    fun decide(url: String, mainFrame: Boolean = false): PageRequest {
        if (mainFrame && url.regionMatches(0, "data:", 0, 5, ignoreCase = true)) return PageRequest.Document
        val u = try {
            URI(url)
        } catch (e: java.net.URISyntaxException) {
            return PageRequest.Blocked
        }
        if (u.scheme != "https" || u.host != HOST || u.port != -1 || u.rawQuery != null || u.rawFragment != null) return PageRequest.Blocked
        val path = u.path ?: return PageRequest.Blocked
        if (path.startsWith(CARD_ASSETS) && path.length > CARD_ASSETS.length && ".." !in path.split('/') && '\\' !in path) {
            return PageRequest.Asset(path.removePrefix("/assets/"))
        }
        if (path.startsWith("/media/")) {
            val sha = path.removePrefix("/media/")
            if (SHA256.matches(sha)) return PageRequest.Media(sha)
        }
        return PageRequest.Blocked
    }

    private val URL_ATTR = Regex("""(?:href|src|action|data)\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** Every URL a page names in href/src attributes (for tests and sanity checks). */
    fun urlsIn(html: String): List<String> = URL_ATTR.findAll(html).map { it.groupValues[1] }.toList()
}
