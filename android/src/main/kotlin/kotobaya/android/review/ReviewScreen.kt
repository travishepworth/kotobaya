package kotobaya.android.review

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.render.CardSide
import kotobaya.sync.client.mint.MintRefusal
import java.io.ByteArrayInputStream

/** The one reused WebView of the review screen: local assets only, no JS, every other URL blocked. */
@SuppressLint("SetJavaScriptEnabled")
internal class CardWebView(context: Context, private val blobs: BlobResolver) {
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
        is PageRequest.Media -> blobs.file(r.sha256)?.takeIf { it.isFile }?.let {
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

/** Plays one cached blob at a time; false when the blob is not on the device (the no-audio state). */
internal class AudioPlayer(private val blobs: BlobResolver) {
    private var player: MediaPlayer? = null

    fun play(sha256: String): Boolean {
        val file = blobs.file(sha256)?.takeIf { it.isFile } ?: return false
        release()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { release() }
            prepare()
            start()
        }
        return true
    }

    fun release() {
        player?.release()
        player = null
    }
}

@StringRes
private fun buttonLabel(b: Button): Int = when (b) {
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

/** The review route: [backend] null means the sync store is not wired yet. */
@Composable
fun ReviewScreen(backend: ReviewBackend<*>?) {
    if (backend == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(T(R.string.review_not_ready)) }
        return
    }
    val context = LocalContext.current
    val session = remember(backend) { backend.newSession() }
    val scope = rememberCoroutineScope()
    val audio = remember(backend) { AudioPlayer(backend.blobs) }
    val web = remember(backend) { CardWebView(context, backend.blobs) }
    val state by session.state.collectAsState()
    val meta by session.meta.collectAsState()
    val prefs = backend.prefs()
    DisposableEffect(session) { onDispose { audio.release(); web.view.destroy() } }
    LaunchedEffect(session) {
        withContext(Dispatchers.IO) {
            backend.awaitReady()
            session.start()
        }
    }
    // Autoplay the front's audio once per displayed card.
    val shownToken = (state as? ReviewState.Showing)?.card?.token
    LaunchedEffect(shownToken) {
        val s = state as? ReviewState.Showing ?: return@LaunchedEffect
        if (prefs.autoplay) s.card.front.audio.firstOrNull()?.let { audio.play(it) }
    }
    fun io(block: () -> Unit) = scope.launch(Dispatchers.IO) { block() }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(T(R.string.review_furigana))
                Switch(checked = meta.furigana, onCheckedChange = { io { session.toggleFurigana() } })
            }
            TextButton(onClick = { io { session.undo() } }, enabled = meta.canUndo) { Text(T(R.string.review_undo)) }
        }
        if (meta.pendingOps >= ReviewSession.SYNC_PROMPT_AT) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(T(R.string.review_sync_prompt, meta.pendingOps), Modifier.weight(1f))
                backend.syncNow?.let { sync -> TextButton(onClick = sync) { Text(T(R.string.review_sync_now)) } }
            }
        }
        when (val s = state) {
            ReviewState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(T(R.string.review_loading)) }
            ReviewState.Empty -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(T(R.string.review_empty_title)) }
            is ReviewState.Summary -> Summary(s.stats, prefs)
            is ReviewState.Blocked -> {
                Text(T(blockedMessage(s.reason)))
                s.card?.let { CardBody(web, it, CardSide.FRONT, prefs, audio, session, ::io) }
            }
            is ReviewState.Showing -> CardBody(web, s.card, s.side, prefs, audio, session, ::io)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CardBody(
    web: CardWebView,
    card: ShownCard,
    side: CardSide,
    prefs: ReviewPrefs,
    audio: AudioPlayer,
    session: ReviewSession<*>,
    io: (() -> Unit) -> Unit,
) {
    val rendered = if (side == CardSide.FRONT) card.front else card.back
    var dragged = 0f
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable { io { session.flip() } }
            .pointerInput(card.token, side, prefs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { _, dx -> dragged += dx },
                    onDragEnd = {
                        // Swiping grades only once the answer side is visible.
                        if (side == CardSide.BACK && kotlin.math.abs(dragged) > SWIPE_PX) {
                            prefs.swipeButton(right = dragged > 0)?.let { b -> io { session.answer(card.token, b) } }
                        }
                    },
                )
            },
    ) {
        AndroidView(factory = { web.view }, update = { web.show(CardPage.build(rendered)) }, modifier = Modifier.fillMaxSize())
    }
    val hasAudio = rendered.audio.isNotEmpty()
    var missing by remember(card.token, side) { mutableStateOf(false) }
    if (hasAudio) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { missing = !audio.play(rendered.audio.first()) }) { Text(T(R.string.review_play_audio)) }
            if (missing) Text(T(R.string.review_no_audio))
        }
    }
    if (side == CardSide.FRONT) {
        MButton(onClick = { io { session.flip() } }, modifier = Modifier.fillMaxWidth()) { Text(T(R.string.review_show_answer)) }
    } else {
        GradingBar(card, prefs) { b -> io { session.answer(card.token, b) } }
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
private fun androidx.compose.foundation.layout.ColumnScope.Summary(stats: SessionStats, prefs: ReviewPrefs) {
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
