package kotobaya.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** A foreground colour and the container it sits on (a grade button, a state chip, a deck chip). */
@Immutable
data class KbTone(val content: Color, val container: Color)

/** The four review grades (docs/DESIGN.md §2.1 Grades). */
enum class KbGrade { Again, Hard, Good, Easy }

/** Card states, each shown as colour + [glyph] + word (colour is never the only signal). */
enum class KbCardState(val glyph: String) {
    New("✦"),
    Learning("◷"),
    Review("✓"),
    Relearning("↺"),
    Suspended("‖"),
    Leech("⚑"),
}

/** Pitch-accent patterns; the pattern number and name are always shown next to the colour. */
enum class KbPitch { Heiban, Atamadaka, Nakadaka, Odaka }

/** The nine user-pickable deck colours; [id] is the stored value (`deck-<id>` token). [Hai] is the default. */
enum class KbDeckColor(val id: String) {
    Ai("ai"),
    Shu("shu"),
    Matcha("matcha"),
    Kohaku("kohaku"),
    Fuji("fuji"),
    Sora("sora"),
    Sakura("sakura"),
    Aoi("aoi"),
    Hai("hai"),
    ;

    companion object {
        /** The colour stored as [id]; unknown or missing ids fall back to [Hai]. */
        fun fromId(id: String?): KbDeckColor = entries.firstOrNull { it.id == id } ?: Hai
    }
}

fun KbColors.grade(grade: KbGrade): KbTone = when (grade) {
    KbGrade.Again -> KbTone(gradeAgain, gradeAgainContainer)
    KbGrade.Hard -> KbTone(gradeHard, gradeHardContainer)
    KbGrade.Good -> KbTone(gradeGood, gradeGoodContainer)
    KbGrade.Easy -> KbTone(gradeEasy, gradeEasyContainer)
}

fun KbColors.state(state: KbCardState): KbTone = when (state) {
    KbCardState.New -> KbTone(stateNew, stateNewContainer)
    KbCardState.Learning -> KbTone(stateLearning, stateLearningContainer)
    KbCardState.Review -> KbTone(stateReview, stateReviewContainer)
    KbCardState.Relearning -> KbTone(stateRelearning, stateRelearningContainer)
    KbCardState.Suspended -> KbTone(stateSuspended, stateSuspendedContainer)
    KbCardState.Leech -> KbTone(stateLeech, stateLeechContainer)
}

fun KbColors.pitch(pitch: KbPitch): Color = when (pitch) {
    KbPitch.Heiban -> pitchHeiban
    KbPitch.Atamadaka -> pitchAtamadaka
    KbPitch.Nakadaka -> pitchNakadaka
    KbPitch.Odaka -> pitchOdaka
}

fun KbColors.deck(deck: KbDeckColor): KbTone = when (deck) {
    KbDeckColor.Ai -> KbTone(deckAi, deckAiContainer)
    KbDeckColor.Shu -> KbTone(deckShu, deckShuContainer)
    KbDeckColor.Matcha -> KbTone(deckMatcha, deckMatchaContainer)
    KbDeckColor.Kohaku -> KbTone(deckKohaku, deckKohakuContainer)
    KbDeckColor.Fuji -> KbTone(deckFuji, deckFujiContainer)
    KbDeckColor.Sora -> KbTone(deckSora, deckSoraContainer)
    KbDeckColor.Sakura -> KbTone(deckSakura, deckSakuraContainer)
    KbDeckColor.Aoi -> KbTone(deckAoi, deckAoiContainer)
    KbDeckColor.Hai -> KbTone(deckHai, deckHaiContainer)
}

/** Feedback tones for banners and toasts. */
enum class KbFeedback { Info, Success, Warning, Danger }

fun KbColors.feedback(kind: KbFeedback): KbTone = when (kind) {
    KbFeedback.Info -> KbTone(onPrimaryContainer, primaryContainer)
    KbFeedback.Success -> KbTone(onSuccessContainer, successContainer)
    KbFeedback.Warning -> KbTone(onWarningContainer, warningContainer)
    KbFeedback.Danger -> KbTone(onDangerContainer, dangerContainer)
}

/** One [Shape] per radius token (DESIGN §2.3): sm inputs, md buttons/rows, lg cards/panels, xl dialogs/sheets. */
object KbShapeSet {
    val xs: Shape = RoundedCornerShape(KbRadius.xs)
    val sm: Shape = RoundedCornerShape(KbRadius.sm)
    val md: Shape = RoundedCornerShape(KbRadius.md)
    val lg: Shape = RoundedCornerShape(KbRadius.lg)
    val xl: Shape = RoundedCornerShape(KbRadius.xl)

    /** Bottom sheets: xl on the top corners only. */
    val sheet: Shape = RoundedCornerShape(topStart = KbRadius.xl, topEnd = KbRadius.xl)
    val full: Shape = RoundedCornerShape(percent = 50)
}
