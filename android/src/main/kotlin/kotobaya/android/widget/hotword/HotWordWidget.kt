package kotobaya.android.widget.hotword

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotobaya.android.MainActivity
import kotobaya.android.R
import kotobaya.android.widget.HotWord
import kotobaya.android.widget.SqliteWidgetSource

private const val HOUR_MS = 3_600_000L

/** Rotating hot-word choice: a new word every hour, in the (stable) order of [candidates]. */
internal fun pickRotating(candidates: List<HotWord>, nowMs: Long): HotWord? {
    if (candidates.isEmpty()) return null
    return candidates[Math.floorMod(Math.floorDiv(nowMs, HOUR_MS), candidates.size.toLong()).toInt()]
}

/** `kotobaya://struggle/word/<entrySeq>`: MainActivity opens the struggle screen on that word. */
internal fun wordIntent(context: Context, entrySeq: Long): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("kotobaya://struggle/word/$entrySeq"), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

private val BG = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1C1B1F))
private val FG = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1E5))
private val MUTED = ColorProvider(day = Color(0xFF605D66), night = Color(0xFFC9C5D0))
private val ACCENT = ColorProvider(day = Color(0xFF6750A4), night = Color(0xFFD0BCFF))

/** 4x3 widget: one rotating hot word (kanji, reading, meaning); tap opens its page. Local DB only, no network. */
class HotWordWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val word = withContext(Dispatchers.IO) {
            pickRotating(SqliteWidgetSource(context).hotCandidates(), System.currentTimeMillis())
        }
        provideContent { HotWordContent(word) }
    }
}

class HotWordWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HotWordWidget()
}

@Composable
internal fun HotWordContent(word: HotWord?) {
    val context = LocalContext.current
    val base = GlanceModifier.fillMaxSize().background(BG).cornerRadius(16.dp).padding(12.dp)
    Column(
        modifier = if (word == null) base else base.clickable(actionStartActivity(wordIntent(context, word.entrySeq))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (word == null) {
            Text(context.getString(R.string.hotword_widget_none), style = TextStyle(color = MUTED, fontSize = 14.sp))
        } else {
            Text(context.getString(R.string.hotword_widget_title), style = TextStyle(color = MUTED, fontSize = 12.sp))
            Text(word.expression, style = TextStyle(color = FG, fontSize = 36.sp, fontWeight = FontWeight.Bold))
            Text(word.reading, style = TextStyle(color = ACCENT, fontSize = 18.sp))
            Text(word.meaning, style = TextStyle(color = MUTED, fontSize = 14.sp), maxLines = 3)
        }
    }
}
