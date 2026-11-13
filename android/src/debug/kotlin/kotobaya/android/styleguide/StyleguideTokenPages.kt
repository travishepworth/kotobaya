package kotobaya.android.styleguide

import androidx.annotation.StringRes
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.reflect.KProperty0
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.ui.theme.KbCardState
import kotobaya.android.ui.theme.KbDeckColor
import kotobaya.android.ui.theme.KbGrade
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbPitch
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.KbTone
import kotobaya.android.ui.theme.KbType
import kotobaya.android.ui.theme.deck
import kotobaya.android.ui.theme.grade
import kotobaya.android.ui.theme.kbDistance
import kotobaya.android.ui.theme.kbTween
import kotobaya.android.ui.theme.pitch
import kotobaya.android.ui.theme.state

// ---- colour ---------------------------------------------------------------------------------------------------

@Composable
fun ColorsBasePage() {
    StyleguideTokens.baseColors.forEach { g -> SgGroup(g.title) { SgFlow { g.tokens.forEach { SgSwatch(it) } } } }
}

@Composable
fun ColorsRolesPage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_grades) {
        SgFlow {
            KbGrade.entries.forEach { g ->
                ToneTile(c.grade(g), gradeLabel(g), "grade${g.name}", glyph = null)
            }
        }
    }
    SgGroup(R.string.sg_group_states) {
        SgFlow {
            KbCardState.entries.forEach { s -> ToneTile(c.state(s), stateLabel(s), "state${s.name}", glyph = s.glyph) }
        }
    }
    SgGroup(R.string.sg_group_pitch) {
        SgFlow { StyleguideTokens.roleColors[2].tokens.forEach { SgSwatch(it) } }
        SgFlow { KbPitch.entries.forEach { p -> PitchLegend(p) } }
    }
    SgGroup(R.string.sg_group_highlight) {
        SgFlow { StyleguideTokens.roleColors[3].tokens.forEach { SgSwatch(it) } }
        HighlightedSentence()
    }
}

@Composable
fun ColorsDecksPage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_decks) {
        SgFlow {
            KbDeckColor.entries.forEach { d ->
                val tone = c.deck(d)
                ToneTile(tone, T(deckLabel(d)), "deck${d.name}", glyph = null, dot = tone.content)
            }
        }
    }
}

/** A container fill with its content colour on top: the way every grade/state/deck pair is used. */
@Composable
private fun ToneTile(
    tone: KbTone,
    label: String,
    token: String,
    glyph: String?,
    dot: androidx.compose.ui.graphics.Color? = null,
) {
    Column(
        Modifier.widthIn(min = 168.dp)
            .background(tone.container, KbTheme.shapes.md)
            .padding(horizontal = KbTheme.space.s3, vertical = KbTheme.space.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2)) {
            if (dot != null) Box(Modifier.size(10.dp).background(dot, CircleShape))
            Text(listOfNotNull(glyph, label).joinToString(" "), style = KbTheme.type.label, color = tone.content)
        }
        Text("$token ${tone.content.hex()}", style = KbTheme.type.caption, color = tone.content)
        Text("${token}Container ${tone.container.hex()}", style = KbTheme.type.caption, color = tone.content)
    }
}

@Composable
fun gradeLabel(g: KbGrade): String = T(
    when (g) {
        KbGrade.Again -> R.string.sg_grade_again
        KbGrade.Hard -> R.string.sg_grade_hard
        KbGrade.Good -> R.string.sg_grade_good
        KbGrade.Easy -> R.string.sg_grade_easy
    },
)

@Composable
fun stateLabel(s: KbCardState): String = T(
    when (s) {
        KbCardState.New -> R.string.sg_state_new
        KbCardState.Learning -> R.string.sg_state_learning
        KbCardState.Review -> R.string.sg_state_review
        KbCardState.Relearning -> R.string.sg_state_relearning
        KbCardState.Suspended -> R.string.sg_state_suspended
        KbCardState.Leech -> R.string.sg_state_leech
    },
)

@StringRes
fun deckLabel(d: KbDeckColor): Int = when (d) {
    KbDeckColor.Ai -> R.string.sg_deck_ai
    KbDeckColor.Shu -> R.string.sg_deck_shu
    KbDeckColor.Matcha -> R.string.sg_deck_matcha
    KbDeckColor.Kohaku -> R.string.sg_deck_kohaku
    KbDeckColor.Fuji -> R.string.sg_deck_fuji
    KbDeckColor.Sora -> R.string.sg_deck_sora
    KbDeckColor.Sakura -> R.string.sg_deck_sakura
    KbDeckColor.Aoi -> R.string.sg_deck_aoi
    KbDeckColor.Hai -> R.string.sg_deck_hai
}

@StringRes
fun pitchLabel(p: KbPitch): Int = when (p) {
    KbPitch.Heiban -> R.string.sg_pitch_heiban
    KbPitch.Atamadaka -> R.string.sg_pitch_atamadaka
    KbPitch.Nakadaka -> R.string.sg_pitch_nakadaka
    KbPitch.Odaka -> R.string.sg_pitch_odaka
}

@Composable
private fun PitchLegend(p: KbPitch) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s1)) {
        Box(Modifier.width(16.dp).height(3.dp).background(KbTheme.colors.pitch(p)))
        Text(T(pitchLabel(p)), style = KbTheme.type.caption, color = KbTheme.colors.text)
    }
}

@Composable
private fun HighlightedSentence() {
    val sentence = T(R.string.sg_jp_sentence)
    val spot = T(R.string.sg_kanji)
    val at = sentence.indexOf(spot)
    val c = KbTheme.colors
    val text = buildAnnotatedString {
        if (at < 0) {
            append(sentence)
        } else {
            append(sentence.substring(0, at))
            withStyle(SpanStyle(background = c.highlight, color = c.onHighlight)) { append(spot) }
            append(sentence.substring(at + spot.length))
        }
    }
    Text(text, style = KbTheme.type.jpBody, color = c.text)
}

// ---- type -----------------------------------------------------------------------------------------------------

@Composable
fun TypePage() {
    val t = KbTheme.type
    StyleguideTokens.typeRoles.forEach { role ->
        val style = role.get(t)
        val sample = when (role.name) {
            "jpHero" -> T(R.string.sg_kanji)
            "jpDisplay" -> T(R.string.sg_jp_headword)
            "reading" -> T(R.string.sg_jp_reading)
            "jpBody" -> T(R.string.sg_jp_sentence)
            "display" -> T(R.string.sg_type_sample_number)
            "overline" -> T(R.string.sg_type_sample_overline).uppercase()
            else -> T(R.string.sg_type_sample)
        }
        Column {
            Text(
                "${role.name} · ${style.fontSize.value.toInt()}sp / ${style.lineHeight.value.toInt()} · ${style.fontWeight?.weight}",
                style = t.caption,
                color = KbTheme.colors.textMuted,
            )
            Text(sample, style = style, color = KbTheme.colors.text)
        }
    }
    SgGroup(R.string.sg_group_furigana) {
        Text(
            "RUBY_SCALE ${KbType.RUBY_SCALE} · RUBY_MIN_SP ${KbType.RUBY_MIN_SP} · RUBY_LINE_HEIGHT ${KbType.RUBY_LINE_HEIGHT}",
            style = t.caption,
            color = KbTheme.colors.textMuted,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s4)) {
            Ruby(T(R.string.sg_furigana_base), T(R.string.sg_furigana_ruby), t.jpDisplay)
            Ruby(T(R.string.sg_furigana_base), T(R.string.sg_furigana_ruby), t.jpBody)
        }
    }
}

/** Base text with its reading centred above at the furigana size. */
@Composable
fun Ruby(base: String, ruby: String, style: androidx.compose.ui.text.TextStyle) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ruby, style = KbTheme.type.ruby(style), color = KbTheme.colors.textMuted)
        Text(base, style = style, color = KbTheme.colors.text)
    }
}

// ---- space, shape, elevation ------------------------------------------------------------------------------------

@Composable
fun SpaceShapePage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_space) {
        StyleguideTokens.space.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, Modifier.width(56.dp), style = KbTheme.type.caption, color = c.text)
                Box(Modifier.width(s.get()).height(12.dp).background(c.primary))
                Text(" ${s.get().value.toInt()}dp", style = KbTheme.type.caption, color = c.textMuted)
            }
        }
    }
    SgGroup(R.string.sg_group_radius) {
        SgFlow {
            StyleguideTokens.radius.forEach { r ->
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(r.get())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).background(c.primaryContainer, shape).border(1.dp, c.borderStrong, shape))
                    Text(r.name, style = KbTheme.type.caption, color = c.textMuted)
                }
            }
        }
    }
    SgGroup(R.string.sg_group_elevation) {
        SgFlow {
            StyleguideTokens.elevation.forEachIndexed { i, e ->
                Surface(
                    Modifier.size(width = 80.dp, height = 56.dp),
                    shape = KbTheme.shapes.lg,
                    color = if (i == 0) c.surface else c.surfaceRaised,
                    shadowElevation = e.get(),
                    border = if (i == 0) androidx.compose.foundation.BorderStroke(1.dp, c.border) else null,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${e.name} ${e.get().value.toInt()}dp", style = KbTheme.type.caption, color = c.text)
                    }
                }
            }
        }
    }
}

// ---- size, breakpoints, motion -----------------------------------------------------------------------------------

@Composable
fun SizeMotionPage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_size) { StyleguideTokens.size.forEach { DpBar(it) } }
    SgGroup(R.string.sg_group_breakpoint) { StyleguideTokens.breakpoint.forEach { DpBar(it) } }
    SgGroup(R.string.sg_group_motion) {
        val durations = listOf(
            "INSTANT" to KbMotion.INSTANT,
            "FAST" to KbMotion.FAST,
            "BASE" to KbMotion.BASE,
            "SLOW" to KbMotion.SLOW,
        )
        var moved by remember { mutableStateOf(false) }
        durations.forEach { (name, ms) ->
            val x by animateDpAsState(
                if (moved) kbDistance(KbMotion.distanceLg * 4, KbTheme.reducedMotion) else 0.dp,
                kbTween(ms, KbMotion.standard),
                label = name,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$name $ms ms", Modifier.width(112.dp), style = KbTheme.type.caption, color = c.text)
                Box(Modifier.width((ms / 2).dp).height(8.dp).background(c.primaryContainer))
                Box(Modifier.offset(x = x).size(12.dp).background(c.primary, CircleShape))
            }
        }
        Text(
            "distanceSm ${KbMotion.distanceSm.value.toInt()} · distanceMd ${KbMotion.distanceMd.value.toInt()} · " +
                "distanceLg ${KbMotion.distanceLg.value.toInt()} dp",
            style = KbTheme.type.caption,
            color = c.textMuted,
        )
        OutlinedButton(onClick = { moved = !moved }, shape = KbTheme.shapes.md) { Text(T(R.string.sg_play), style = KbTheme.type.label) }
    }
    SgGroup(R.string.sg_group_easing) {
        SgFlow {
            listOf(
                "standard" to KbMotion.standard,
                "decelerate" to KbMotion.decelerate,
                "accelerate" to KbMotion.accelerate,
                "emphasized" to KbMotion.emphasized,
            ).forEach { (name, e) -> EasingCurve(name, e) }
        }
    }
}

/** A bar as long as the token (capped at the available width), with its name and value. */
@Composable
private fun DpBar(token: KProperty0<Dp>) {
    val c = KbTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(token.name, Modifier.width(96.dp), style = KbTheme.type.caption, color = c.text)
        BoxWithConstraints(Modifier.weight(1f)) {
            val w = minOf(token.get(), maxWidth - 64.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(w).height(8.dp).background(c.primary))
                Text(" ${token.get().value.toInt()}dp", style = KbTheme.type.caption, color = c.textMuted)
            }
        }
    }
}

@Composable
private fun EasingCurve(name: String, easing: Easing) {
    val c = KbTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(72.dp).background(c.surfaceSunken, KbTheme.shapes.sm).padding(KbTheme.space.s2)) {
            val path = Path()
            val steps = 32
            for (i in 0..steps) {
                val f = i / steps.toFloat()
                val p = Offset(f * size.width, size.height * (1f - easing.transform(f)))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, c.primary, style = Stroke(width = 2.dp.toPx()))
        }
        Text(name, style = KbTheme.type.caption, color = c.textMuted)
    }
}
