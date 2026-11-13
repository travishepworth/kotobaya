package kotobaya.android.review.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.review.IntervalLabel
import kotobaya.android.ui.components.KbKey
import kotobaya.android.ui.components.LARGE_TEXT
import kotobaya.android.ui.components.fontScale
import kotobaya.android.ui.components.kbFocusRing
import kotobaya.android.ui.components.kbPressScale
import kotobaya.android.ui.theme.KbGrade
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.grade
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.fsrs.RatingOutcome

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

/** The grade tone of a button (jpdb's five map onto the four grade colours). */
internal fun gradeOf(b: Button): KbGrade = when (b) {
    Button.AGAIN, Button.NOTHING, Button.SOMETHING -> KbGrade.Again
    Button.HARD -> KbGrade.Hard
    Button.GOOD, Button.OKAY -> KbGrade.Good
    Button.EASY -> KbGrade.Easy
}

/** Height of one grade button (DESIGN §2.3: 72 dp). */
internal val GRADE_HEIGHT = 72.dp

/** Whether the bar is laid out in two rows at [widthDp] for [count] buttons (narrow or large text). */
@Composable
internal fun gradeBarTwoRows(widthDp: Float, count: Int): Boolean = fontScale() >= LARGE_TEXT || widthDp / count < 76f

/**
 * The grade bar (DESIGN §3): equal buttons, each a tonal container with a 3 dp top rule in its grade colour, the
 * grade name, the interval preview and (with a keyboard) its key. Good is the default and fully outlined. Two rows
 * when narrow or at large text. [previews] null hides intervals (drills do not reschedule).
 */
@Composable
internal fun GradeBar(
    buttons: List<Button>,
    previews: List<RatingOutcome>?,
    keyHints: Boolean,
    enabled: Boolean,
    onGrade: (Button) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val twoRows = gradeBarTwoRows(maxWidth.value, buttons.size)
        val rows = if (twoRows) buttons.chunked((buttons.size + 1) / 2) else listOf(buttons)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { b ->
                        val outcome = previews?.firstOrNull { it.rating == b.rating }
                        val index = buttons.indexOf(b) + 1
                        GradeButton(b, outcome?.let { IntervalLabel.of(it.intervalMs) }, if (keyHints) index.toString() else null, enabled, Modifier.weight(1f).fillMaxHeight()) { onGrade(b) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GradeButton(b: Button, interval: IntervalLabel?, key: String?, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tone = KbTheme.colors.grade(gradeOf(b))
    val default = b == Button.GOOD || b == Button.OKAY
    val shape = RoundedCornerShape(KbRadius.md)
    val interaction = remember { MutableInteractionSource() }
    val name = T(buttonLabel(b))
    val intervalText = interval?.let { T(it.res, it.count) }
    Box(
        modifier
            .heightIn(min = GRADE_HEIGHT)
            .kbPressScale(interaction)
            .kbFocusRing(interaction, KbRadius.md)
            .clip(shape)
            .background(tone.container)
            .then(if (default) Modifier.border(2.dp, tone.content, shape) else Modifier)
            .clickable(interaction, ripple(color = tone.content), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(name, intervalText).joinToString(", ") },
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(3.dp).clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)).background(tone.content).align(Alignment.TopCenter))
        Column(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(name, style = KbTheme.type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = KbTheme.type.body.fontSize), color = tone.content, textAlign = TextAlign.Center)
            if (intervalText != null) Text(intervalText, style = KbTheme.type.caption, color = KbTheme.colors.text, textAlign = TextAlign.Center)
        }
        if (key != null) KbKey(key, Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp), small = true)
    }
}
