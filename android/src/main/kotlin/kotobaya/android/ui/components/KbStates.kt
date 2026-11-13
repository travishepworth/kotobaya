package kotobaya.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.feedback
import kotobaya.android.ui.theme.kbTween

/** The tone of an [KbEmptyState]: neutral icon disc, a check (all clear) or an error with Retry. */
enum class KbEmptyTone { Neutral, Clear, Error }

/**
 * Every list's empty / error state (DESIGN §3 Empty state): an icon disc, title, one-line body and at most one
 * action. [page] centres it in the available space; inline keeps it compact inside a panel.
 */
@Composable
fun KbEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = KbIcons.Info,
    tone: KbEmptyTone = KbEmptyTone.Neutral,
    page: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = KbTheme.colors
    val disc = when (tone) {
        KbEmptyTone.Neutral -> c.surfaceSunken to c.textMuted
        KbEmptyTone.Clear -> c.successContainer to c.onSuccessContainer
        KbEmptyTone.Error -> c.dangerContainer to c.onDangerContainer
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = if (page) 48.dp else 24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(if (page) 64.dp else 52.dp).clip(CircleShape).background(disc.first), contentAlignment = Alignment.Center) {
            Icon(if (tone == KbEmptyTone.Clear) KbIcons.Check else icon, null, tint = disc.second, modifier = Modifier.size(if (page) 30.dp else 24.dp))
        }
        Text(
            title,
            style = if (page) KbTheme.type.title else KbTheme.type.titleSm,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 420.dp),
        )
        if (body != null) {
            Text(body, style = KbTheme.type.bodySm, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        }
        if (action != null) Box(Modifier.padding(top = 8.dp)) { action() }
    }
}

/** Inline banner (feedback tone): icon, text, optional action, under a header. */
@Composable
fun KbBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: KbFeedback = KbFeedback.Info,
    icon: ImageVector = if (kind == KbFeedback.Info || kind == KbFeedback.Success) KbIcons.Info else KbIcons.Alert,
    action: (@Composable () -> Unit)? = null,
) {
    val tone = KbTheme.colors.feedback(kind)
    val stacked = fontScale() >= LARGE_TEXT
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(KbRadius.md))
            .background(tone.container)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (stacked) {
            Column {
                BannerText(text, icon, tone.content, Modifier.padding(vertical = 6.dp))
                if (action != null) Box(Modifier.align(Alignment.End)) { action() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BannerText(text, icon, tone.content, Modifier.weight(1f).padding(vertical = 6.dp))
                if (action != null) action()
            }
        }
    }
}

@Composable
private fun BannerText(text: String, icon: ImageVector, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(text, style = KbTheme.type.bodySm, color = color)
    }
}

/** A skeleton block of a given size (reserves the real layout: no shift on load). */
@Composable
fun KbSkeleton(modifier: Modifier = Modifier, height: Dp = 16.dp, radius: Dp = KbRadius.xs) {
    // Static on purpose: a calm placeholder, and nothing animates forever (reduced motion is honoured for free).
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(KbTheme.colors.surfaceSunken),
    )
}

/** [count] list-row skeletons (title line + supporting line), the loading state of every list. */
@Composable
fun KbSkeletonRows(count: Int, modifier: Modifier = Modifier, leading: Boolean = false) {
    Column(modifier.fillMaxWidth()) {
        repeat(count) { i ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (leading) KbSkeleton(Modifier.size(12.dp), 12.dp, 6.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KbSkeleton(Modifier.fillMaxWidth(if (i % 2 == 0) 0.45f else 0.6f), 18.dp)
                    KbSkeleton(Modifier.fillMaxWidth(if (i % 3 == 0) 0.8f else 0.7f), 12.dp)
                }
            }
            if (i < count - 1) KbDivider(inset = 16.dp)
        }
    }
}

/** A 4 dp progress track (review, imports, drills); [fraction] 0..1. */
@Composable
fun KbProgressBar(fraction: Float, modifier: Modifier = Modifier, label: String? = null) {
    val c = KbTheme.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), kbTween(KbMotion.SLOW), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.surfaceSunken)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                if (label != null) stateDescription = label
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(2.dp)).background(c.primary))
    }
}

/**
 * The ensō (円相), the vermilion brush circle reserved for "review finished" (DESIGN §1). Drawn as an open,
 * slightly tapering stroke. [size] defaults to the finished state's 120 dp; mini states use 56 dp.
 */
@Composable
fun KbEnso(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val brand = KbTheme.colors.brand
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.085f
        val inset = stroke
        val rect = Size(w - 2 * inset, w - 2 * inset)
        // the main sweep, with a gap at the top right (where the brush lifts)
        drawArc(brand, startAngle = -60f, sweepAngle = 318f, useCenter = false, topLeft = Offset(inset, inset), size = rect, style = Stroke(stroke, cap = StrokeCap.Round))
        // a thinner inner pass gives the stroke its uneven, hand-made edge
        val i2 = inset + stroke * 0.35f
        drawArc(
            brand.copy(alpha = 0.55f), startAngle = 150f, sweepAngle = 150f, useCenter = false,
            topLeft = Offset(i2, i2), size = Size(w - 2 * i2, w - 2 * i2), style = Stroke(stroke * 0.45f, cap = StrokeCap.Round),
        )
    }
}

/** A full-size centred box (page-level empty / loading states). */
@Composable
fun KbCentered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** A small round count badge (due counts). */
@Composable
fun KbCountBadge(count: Int, modifier: Modifier = Modifier, strong: Boolean = true) {
    val c = KbTheme.colors
    Box(
        modifier
            .heightIn(min = 24.dp)
            .widthIn(min = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (strong) c.primary else c.surfaceSunken)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = KbTheme.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = if (strong) c.onPrimary else c.textMuted)
    }
}

/** Width helper for skeleton text lines. */
@Composable
fun KbSkeletonLine(width: Dp, height: Dp = 14.dp) = KbSkeleton(Modifier.width(width), height)
