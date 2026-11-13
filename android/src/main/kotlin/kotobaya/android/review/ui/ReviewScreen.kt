package kotobaya.android.review.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button as MButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.File
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.review.CardPage
import kotobaya.android.review.IntervalLabel
import kotobaya.android.review.PageRequest
import kotobaya.android.review.ReviewIntents
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewUiState
import kotobaya.android.review.SessionStats
import kotobaya.android.review.ShownCard
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.render.CardSide
import kotobaya.sync.client.mint.MintRefusal

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

@StringRes
internal fun buttonLabel(b: Button): Int = when (b) {
    Button.AGAIN -> R.string.review_button_again
    Button.HARD -> R.string.review_button_hard
    Button.GOOD -> R.string.review_button_good
    Button.EASY -> R.string.review_button_easy
    Button.NOTHING -> R.string.review_button_nothing
    Button.SOMETHING -> R.string.review_button_something
    Button.OKAY -> R.string.review_button_okay
}

@StringRes
private fun blockedMessage(r: MintRefusal): Int = when (r) {
    MintRefusal.NotPaired -> R.string.review_blocked_not_paired
    MintRefusal.RebuildPending -> R.string.review_blocked_rebuild
    is MintRefusal.ClockGuard, is MintRefusal.ClockFarFromServer -> R.string.review_blocked_clock
    else -> R.string.review_blocked_other
}

/** The review screen: [state] from the ReviewViewModel, every action through [intents]. */
@Composable
fun ReviewScreen(state: ReviewUiState, intents: ReviewIntents) {
    if (!state.available) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(T(R.string.review_not_ready)) }
        return
    }
    val context = LocalContext.current
    val web = remember { CardWebView(context, intents::mediaFile) }
    DisposableEffect(web) { onDispose { web.view.destroy() } }
    val meta = state.meta
    val prefs = state.prefs

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T(R.string.review_furigana))
                Switch(checked = meta.furigana, onCheckedChange = { intents.toggleFurigana() })
            }
            TextButton(onClick = intents::undo, enabled = meta.canUndo) { Text(T(R.string.review_undo)) }
        }
        (state.review as? ReviewState.Showing)?.card?.let { ReviewActionsBar(state.actions, it.cardId, intents) }
        if (state.syncPrompt) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(T(R.string.review_sync_prompt, meta.pendingOps), Modifier.weight(1f))
                if (state.canSync) TextButton(onClick = intents::syncNow) { Text(T(R.string.review_sync_now)) }
            }
        }
        when (val s = state.review) {
            ReviewState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(T(R.string.review_loading)) }
            ReviewState.Empty -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(T(R.string.review_empty_title)) }
            is ReviewState.Summary -> Summary(s.stats, prefs)
            is ReviewState.Blocked -> {
                Text(T(blockedMessage(s.reason)))
                s.card?.let { CardBody(web, it, CardSide.FRONT, prefs, state.audioMissing, intents) }
            }
            is ReviewState.Showing -> CardBody(web, s.card, s.side, prefs, state.audioMissing, intents)
        }
    }
}

@Composable
private fun ColumnScope.CardBody(
    web: CardWebView,
    card: ShownCard,
    side: CardSide,
    prefs: ReviewPrefs,
    audioMissing: Boolean,
    intents: ReviewIntents,
) {
    val rendered = if (side == CardSide.FRONT) card.front else card.back
    var dragged = 0f
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable { intents.flip() }
            .pointerInput(card.token, side, prefs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { _, dx -> dragged += dx },
                    onDragEnd = {
                        // Swiping grades only once the answer side is visible.
                        if (side == CardSide.BACK && kotlin.math.abs(dragged) > SWIPE_PX) {
                            prefs.swipeButton(right = dragged > 0)?.let { b -> intents.answer(card.token, b) }
                        }
                    },
                )
            },
    ) {
        AndroidView(factory = { web.view }, update = { web.show(CardPage.build(rendered)) }, modifier = Modifier.fillMaxSize())
    }
    card.noExamplePattern?.let { pattern ->
        Text(T(R.string.review_no_example_sentence))
        if (pattern.isNotEmpty()) Text(T(R.string.review_no_example_pattern, pattern))
    }
    if (rendered.audio.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { intents.playAudio(rendered.audio.first()) }) { Text(T(R.string.review_play_audio)) }
            if (audioMissing) Text(T(R.string.review_no_audio))
        }
    }
    if (side == CardSide.FRONT) {
        MButton(onClick = intents::flip, modifier = Modifier.fillMaxWidth()) { Text(T(R.string.review_show_answer)) }
    } else {
        GradingBar(card, prefs) { b -> intents.answer(card.token, b) }
    }
}

/** The four or five grading buttons with the core's interval previews. */
@Composable
private fun GradingBar(card: ShownCard, prefs: ReviewPrefs, onGrade: (Button) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in prefs.buttons) {
            val outcome = card.previews.firstOrNull { it.rating == b.rating }
            MButton(onClick = { onGrade(b) }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(T(buttonLabel(b)))
                    outcome?.let { o -> IntervalLabel.of(o.intervalMs).let { Text(T(it.res, it.count)) } }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Summary(stats: SessionStats, prefs: ReviewPrefs) {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(T(R.string.review_summary_title))
        Text(T(R.string.review_summary_answered, stats.answered))
        Text(T(R.string.review_summary_time, (stats.totalMs / 1000).toInt()))
        for (b in prefs.buttons + Button.entries.filter { it !in prefs.buttons }) {
            stats.byButton[b]?.let { Text(T(R.string.review_summary_buttons, T(buttonLabel(b)), it)) }
        }
    }
}

private const val SWIPE_PX = 120f
