package kotobaya.android.review

import kotobaya.contracts.render.RenderedCard
import java.net.URI

/** What the card WebView may fetch. Everything else is blocked: the card view makes no network request. */
sealed interface PageRequest {
    /** A file of `assets/card/`, as an Android asset path (`card/card.css`). */
    data class Asset(val path: String) : PageRequest

    /** A cached blob (sha256 hex), served from local storage only. */
    data class Media(val sha256: String) : PageRequest

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

    /** Decides one request of the WebView: only `https://HOST/assets/card/...` and `https://HOST/media/<sha256>` pass. */
    fun decide(url: String): PageRequest {
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
