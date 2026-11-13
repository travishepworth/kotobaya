package kotobaya.android.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * A tween on the motion tokens (DESIGN §2.4): [durationMs] is one of [KbMotion]'s durations, [easing] one of its
 * curves. Use for colour, alpha and size changes; movement goes through [kbDistance] so reduced motion drops it.
 */
fun <T> kbTween(durationMs: Int = KbMotion.BASE, easing: Easing = KbMotion.standard, delayMs: Int = 0): FiniteAnimationSpec<T> =
    tween(durationMillis = durationMs, delayMillis = delayMs, easing = easing)

/** A movement distance under the reduced-motion rule: 0 when [reducedMotion] (pass `KbTheme.reducedMotion`). */
fun kbDistance(distance: Dp, reducedMotion: Boolean): Dp = if (reducedMotion) KbSpace.s0 else distance

/** The press scale of interactive components (applied over [KbMotion.INSTANT]). */
const val KB_PRESS_SCALE: Float = 0.98f
