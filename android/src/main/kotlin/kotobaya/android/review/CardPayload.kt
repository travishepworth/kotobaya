package kotobaya.android.review

import android.util.Log
import kotobaya.android.report.ClientErrors
import kotobaya.android.vm.ScreenLog
import kotobaya.contracts.render.RenderedCard

/**
 * What the review card WebView is given (AND-CARDHTML): a full page with something visible on it, or a failure the
 * screen shows as its own (i18n) error state. The WebView is never handed an empty or blank document.
 */
sealed interface CardPayload {
    /** A complete card document; [html] is never blank and its card has visible content. */
    data class Page(val html: String) : CardPayload

    /** Nothing to load: [reason] is a stable, non-personal description (`blank vocab/meaning front`). */
    data class Failed(val reason: String) : CardPayload
}

/** Builds [CardPayload]s and reports the failures through [failures]. */
class CardPayloads(private val failures: CardFailures = CardFailures.DEFAULT) {
    /** The page for [card] in the app theme, or [CardPayload.Failed] (logged and reported) when there is nothing to show. */
    fun of(card: RenderedCard, dark: Boolean): CardPayload {
        val reason = when {
            card.html.isBlank() -> "empty render ${describe(card)}"
            !hasVisibleContent(card.html) -> "blank render ${describe(card)}"
            else -> null
        }
        if (reason != null) {
            failures.failed(reason)
            return CardPayload.Failed(reason)
        }
        val page = try {
            CardPage.themed(card, dark)
        } catch (e: RuntimeException) {
            val r = "page build failed ${describe(card)}: ${e.javaClass.simpleName}"
            failures.failed(r)
            return CardPayload.Failed(r)
        }
        return CardPayload.Page(page)
    }

    /** The WebView could not load a page it was given (main frame; [what] is the error code or HTTP status). */
    fun loadFailed(what: String): CardPayload.Failed {
        val r = "card page load failed: $what"
        failures.failed(r)
        return CardPayload.Failed(r)
    }

    companion object {
        private val TAG = Regex("<[^>]*>")
        private val ROOT_CLASS = Regex("""^\s*<div class="kb-card kb-([a-z_]+) kb-kind-([a-z_]+) kb-(front|back)"""")

        /**
         * Whether [html] shows anything: text outside tags, an image, or an audio button (the same rule as the core's
         * `frontIsEmpty`).
         */
        fun hasVisibleContent(html: String): Boolean {
            if ("<img" in html || "data-kb-audio" in html) return true
            val text = TAG.replace(html, "").replace("&nbsp;", " ").replace("&#160;", " ")
            return text.any { !it.isWhitespace() && it != '​' && it != '　' }
        }

        /** `vocab/meaning front`: the root classes only, never card content. */
        fun describe(card: RenderedCard): String =
            ROOT_CLASS.find(card.html)?.destructured?.let { (base, kind, side) -> "$base/$kind $side" } ?: "unknown card"
    }
}

/** Where card display failures go: always a tagged log line; a client-error report once per distinct reason. */
class CardFailures(
    private val log: (String) -> Unit,
    private val report: (String) -> Unit,
    private val maxReports: Int = MAX_REPORTS,
) {
    private val reported = HashSet<String>()

    fun failed(reason: String) {
        runCatching { log(reason) }
        val send = synchronized(reported) { reported.size < maxReports && reported.add(reason) }
        if (send) runCatching { report(reason) }
    }

    companion object {
        /** Distinct reasons reported per process; identical ones also fold in the client-error queue. */
        const val MAX_REPORTS = 10

        val DEFAULT = CardFailures(
            log = { r -> runCatching { Log.w(ScreenLog.TAG, "review card not shown: $r") } },
            report = { r -> ClientErrors.exception(IllegalStateException("review card not shown: $r"), "ReviewCard") },
        )
    }
}
