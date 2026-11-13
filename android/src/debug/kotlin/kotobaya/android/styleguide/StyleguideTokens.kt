package kotobaya.android.styleguide

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotobaya.android.R
import kotobaya.android.ui.theme.KbBreakpoint
import kotobaya.android.ui.theme.KbColors
import kotobaya.android.ui.theme.KbElevation
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbSize
import kotobaya.android.ui.theme.KbSpace
import kotobaya.android.ui.theme.KbTextStyles

/** A group of semantic colours as DESIGN.md §2.1 lists them. */
class ColorGroup(@StringRes val title: Int, val tokens: List<KProperty1<KbColors, Color>>)

/**
 * The catalogue the styleguide renders: every generated token, by name. StyleguideCatalogueTest fails when Tokens.kt
 * gains a token that is not listed here, so a new token cannot silently miss the styleguide.
 */
object StyleguideTokens {
    val baseColors: List<ColorGroup> = listOf(
        ColorGroup(
            R.string.sg_group_surfaces,
            listOf(
                KbColors::bg, KbColors::surface, KbColors::surfaceSunken, KbColors::surfaceRaised, KbColors::surfaceHover,
                KbColors::surfaceSelected, KbColors::border, KbColors::borderStrong, KbColors::scrim,
            ),
        ),
        ColorGroup(R.string.sg_group_text, listOf(KbColors::text, KbColors::textMuted, KbColors::textDisabled)),
        ColorGroup(
            R.string.sg_group_action,
            listOf(
                KbColors::primary, KbColors::onPrimary, KbColors::primaryHover, KbColors::primaryContainer,
                KbColors::onPrimaryContainer, KbColors::link, KbColors::focus, KbColors::brand,
            ),
        ),
        ColorGroup(
            R.string.sg_group_feedback,
            listOf(
                KbColors::danger, KbColors::onDanger, KbColors::dangerContainer, KbColors::onDangerContainer,
                KbColors::warningContainer, KbColors::onWarningContainer, KbColors::successContainer,
                KbColors::onSuccessContainer,
            ),
        ),
    )

    val roleColors: List<ColorGroup> = listOf(
        ColorGroup(
            R.string.sg_group_grades,
            listOf(
                KbColors::gradeAgain, KbColors::gradeAgainContainer, KbColors::gradeHard, KbColors::gradeHardContainer,
                KbColors::gradeGood, KbColors::gradeGoodContainer, KbColors::gradeEasy, KbColors::gradeEasyContainer,
            ),
        ),
        ColorGroup(
            R.string.sg_group_states,
            listOf(
                KbColors::stateNew, KbColors::stateNewContainer, KbColors::stateLearning, KbColors::stateLearningContainer,
                KbColors::stateReview, KbColors::stateReviewContainer, KbColors::stateRelearning,
                KbColors::stateRelearningContainer, KbColors::stateSuspended, KbColors::stateSuspendedContainer,
                KbColors::stateLeech, KbColors::stateLeechContainer,
            ),
        ),
        ColorGroup(
            R.string.sg_group_pitch,
            listOf(
                KbColors::pitchLine, KbColors::pitchHeiban, KbColors::pitchAtamadaka, KbColors::pitchNakadaka,
                KbColors::pitchOdaka,
            ),
        ),
        ColorGroup(R.string.sg_group_highlight, listOf(KbColors::highlight, KbColors::onHighlight)),
    )

    val deckColors: ColorGroup = ColorGroup(
        R.string.sg_group_decks,
        listOf(
            KbColors::deckAi, KbColors::deckAiContainer, KbColors::deckShu, KbColors::deckShuContainer,
            KbColors::deckMatcha, KbColors::deckMatchaContainer, KbColors::deckKohaku, KbColors::deckKohakuContainer,
            KbColors::deckFuji, KbColors::deckFujiContainer, KbColors::deckSora, KbColors::deckSoraContainer,
            KbColors::deckSakura, KbColors::deckSakuraContainer, KbColors::deckAoi, KbColors::deckAoiContainer,
            KbColors::deckHai, KbColors::deckHaiContainer,
        ),
    )

    val allColors: List<KProperty1<KbColors, Color>> =
        (baseColors + roleColors + deckColors).flatMap { it.tokens }

    val typeRoles: List<KProperty1<KbTextStyles, TextStyle>> = listOf(
        KbTextStyles::jpHero, KbTextStyles::jpDisplay, KbTextStyles::display, KbTextStyles::headline,
        KbTextStyles::title, KbTextStyles::titleSm, KbTextStyles::jpBody, KbTextStyles::reading, KbTextStyles::bodyLg,
        KbTextStyles::body, KbTextStyles::bodySm, KbTextStyles::label, KbTextStyles::caption, KbTextStyles::overline,
    )

    val space: List<KProperty0<Dp>> = listOf(
        KbSpace::s0, KbSpace::s0_5, KbSpace::s1, KbSpace::s2, KbSpace::s3, KbSpace::s4, KbSpace::s5, KbSpace::s6,
        KbSpace::s8, KbSpace::s10, KbSpace::s12, KbSpace::s16, KbSpace::s20, KbSpace::s24,
    )

    val radius: List<KProperty0<Dp>> =
        listOf(KbRadius::xs, KbRadius::sm, KbRadius::md, KbRadius::lg, KbRadius::xl, KbRadius::full)

    val elevation: List<KProperty0<Dp>> =
        listOf(KbElevation::level0, KbElevation::level1, KbElevation::level2, KbElevation::level3)

    val size: List<KProperty0<Dp>> = listOf(
        KbSize::touchMin, KbSize::control, KbSize::controlSm, KbSize::gradeButton, KbSize::iconSm, KbSize::icon,
        KbSize::iconLg, KbSize::topBar, KbSize::bottomNav, KbSize::sidebar, KbSize::rail, KbSize::measure,
        KbSize::reviewCard, KbSize::content,
    )

    val breakpoint: List<KProperty0<Dp>> =
        listOf(KbBreakpoint::medium, KbBreakpoint::expanded, KbBreakpoint::large, KbBreakpoint::xlarge)
}

/** `#RRGGBB` or `#AARRGGBB` when translucent. */
fun Color.hex(): String {
    val argb = toArgb()
    return if (alpha >= 1f) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
}
