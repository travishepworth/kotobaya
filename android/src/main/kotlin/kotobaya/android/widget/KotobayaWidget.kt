package kotobaya.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.R
import org.koin.core.context.GlobalContext

internal val SMALL = DpSize(110.dp, 40.dp)
internal val MEDIUM = DpSize(250.dp, 110.dp)
internal val LARGE = DpSize(250.dp, 180.dp)

/** Where the widget and the tile get their numbers: the Koin-bound [WidgetData], or an empty snapshot if it is not up. */
object WidgetSnapshots {
    fun load(): WidgetSnapshot =
        runCatching { GlobalContext.get().get<WidgetData>().snapshot() }.getOrDefault(WidgetSnapshot.EMPTY)
}

/** One responsive widget: 2x1 (counts), 4x2 (+ forecast and progress), 4x3 (+ a hot word). Local DB only, no network. */
class KotobayaWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = withContext(Dispatchers.IO) { WidgetSnapshots.load() }
        provideContent { WidgetContent(snapshot, LocalSize.current) }
    }
}

class KotobayaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KotobayaWidget()
}

private val BG = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1B1F))
private val FG = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1E5))
private val MUTED = ColorProvider(day = Color(0xFF605D66), night = Color(0xFFC9C5D0))
private val ACCENT = ColorProvider(day = Color(0xFF6750A4), night = Color(0xFFD0BCFF))
private val TRACK = ColorProvider(day = Color(0xFFE7E0EC), night = Color(0xFF49454F))

internal enum class WidgetSize { SMALL, MEDIUM, LARGE }

internal fun sizeFor(size: DpSize): WidgetSize = when {
    size.height >= LARGE.height && size.width >= LARGE.width -> WidgetSize.LARGE
    size.height >= MEDIUM.height && size.width >= MEDIUM.width -> WidgetSize.MEDIUM
    else -> WidgetSize.SMALL
}

/** The widget body; a pure function of [snapshot] and the available [size] so it can be composed in tests. */
@Composable
internal fun WidgetContent(snapshot: WidgetSnapshot, size: DpSize) {
    val context = LocalContext.current
    val kind = sizeFor(size)
    Column(
        modifier = GlanceModifier.fillMaxSize().background(BG).cornerRadius(16.dp).padding(10.dp)
            .clickable(actionStartActivity(WidgetIntents.review(context))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Counts(snapshot)
        if (kind != WidgetSize.SMALL) {
            Progress(snapshot)
            Forecast(snapshot.forecast)
        }
        if (kind == WidgetSize.LARGE) HotWordRow(snapshot.hotWord)
    }
}

@Composable
private fun Counts(s: WidgetSnapshot) {
    val context = LocalContext.current
    if (s.isEmpty) {
        Text(context.getString(R.string.widget_all_done), style = TextStyle(color = FG, fontSize = 16.sp, fontWeight = FontWeight.Bold))
    } else {
        Text(
            context.getString(R.string.widget_counts, s.due, s.new),
            style = TextStyle(color = FG, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun Progress(s: WidgetSnapshot) {
    val context = LocalContext.current
    val total = s.doneToday + s.due + s.new
    Text(context.getString(R.string.widget_progress, s.doneToday, total), style = TextStyle(color = MUTED, fontSize = 12.sp))
    Row(modifier = GlanceModifier.fillMaxWidth().height(6.dp).background(TRACK).cornerRadius(3.dp)) {
        val filled = (s.progress * 100).toInt().coerceIn(0, 100)
        if (filled > 0) Box(modifier = GlanceModifier.width((filled * 2).dp).height(6.dp).background(ACCENT).cornerRadius(3.dp)) {}
    }
}

@Composable
private fun Forecast(forecast: List<Int>) {
    val context = LocalContext.current
    val max = forecast.maxOrNull()?.coerceAtLeast(1) ?: 1
    Text(context.getString(R.string.widget_forecast), style = TextStyle(color = MUTED, fontSize = 12.sp))
    Row(verticalAlignment = Alignment.Bottom, modifier = GlanceModifier.fillMaxWidth().height(32.dp)) {
        forecast.forEach { n ->
            val h = if (n == 0) 1 else (n * 30 / max).coerceAtLeast(2)
            Box(modifier = GlanceModifier.width(8.dp).height(h.dp).background(if (n == 0) TRACK else ACCENT)) {}
            Box(modifier = GlanceModifier.width(1.dp).height(1.dp)) {}
        }
    }
}

@Composable
private fun HotWordRow(word: HotWord?) {
    val context = LocalContext.current
    if (word == null) {
        Text(context.getString(R.string.widget_no_hot_word), style = TextStyle(color = MUTED, fontSize = 12.sp))
        return
    }
    Column(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(WidgetIntents.word(context, word.entrySeq)))) {
        Text(word.expression, style = TextStyle(color = FG, fontSize = 20.sp, fontWeight = FontWeight.Bold))
        Text(word.reading, style = TextStyle(color = ACCENT, fontSize = 13.sp))
        Text(word.meaning, style = TextStyle(color = MUTED, fontSize = 12.sp), maxLines = 1)
    }
}
