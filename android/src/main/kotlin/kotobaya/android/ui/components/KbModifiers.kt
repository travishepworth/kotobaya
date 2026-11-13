package kotobaya.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KB_PRESS_SCALE
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.kbTween

/**
 * The focus ring of every interactive component (DESIGN §3): 2 dp in `focus`, drawn 2 dp outside the bounds so the
 * layout never moves when focus arrives. [radius] is the component's corner radius.
 */
fun Modifier.kbFocusRing(interaction: InteractionSource, radius: Dp): Modifier = composed {
    val focused by interaction.collectIsFocusedAsState()
    val color = KbTheme.colors.focus
    drawWithContent {
        drawContent()
        if (focused) {
            val w = 2.dp.toPx()
            val gap = 2.dp.toPx() + w / 2
            drawRoundRect(
                color = color,
                topLeft = Offset(-gap, -gap),
                size = Size(size.width + 2 * gap, size.height + 2 * gap),
                cornerRadius = CornerRadius(radius.toPx() + gap),
                style = Stroke(w),
            )
        }
    }
}

/** Press feedback: scale 0.98 over `instant` (no movement under reduced motion, only the ripple). */
fun Modifier.kbPressScale(interaction: InteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = KbTheme.reducedMotion
    val scale by animateFloatAsState(if (pressed && !reduced) KB_PRESS_SCALE else 1f, kbTween(KbMotion.INSTANT))
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** The current font scale (1.0 = default, 2.0 = 200 %). Layouts reflow above [LARGE_TEXT]. */
@Composable
fun fontScale(): Float = LocalDensity.current.fontScale

/** From this font scale up, rows stack their trailing content under the text and button groups go vertical. */
const val LARGE_TEXT: Float = 1.5f
