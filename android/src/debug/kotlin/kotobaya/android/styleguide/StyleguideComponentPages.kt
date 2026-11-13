@file:OptIn(ExperimentalMaterial3Api::class)

package kotobaya.android.styleguide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.ui.theme.KbCardState
import kotobaya.android.ui.theme.KbDeckColor
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbGrade
import kotobaya.android.ui.theme.KbPitch
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.deck
import kotobaya.android.ui.theme.feedback
import kotobaya.android.ui.theme.grade
import kotobaya.android.ui.theme.pitch
import kotobaya.android.ui.theme.state

// Specimens of DESIGN.md §3 built from stock Material 3 + tokens. They show how each component should look; the
// production Compose components are UI-APP's (same anatomy, same tokens).

// ---- actions and selection ---------------------------------------------------------------------------------------

@Composable
fun ActionsPage() {
    val c = KbTheme.colors
    val md = Modifier.heightIn(min = KbTheme.size.control)
    SgGroup(R.string.sg_group_buttons) {
        SgFlow {
            Button(onClick = {}, md, shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_start)) }
            FilledTonalButton(onClick = {}, md, shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_add)) }
            OutlinedButton(onClick = {}, md, border = BorderStroke(1.dp, c.borderStrong), shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_import)) }
            TextButton(onClick = {}, md, shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_cancel)) }
            Button(
                onClick = {},
                shape = KbTheme.shapes.md,
                md,
                colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger),
            ) { Text(T(R.string.sg_btn_delete)) }
        }
        SgFlow {
            Button(
                onClick = {},
                shape = KbTheme.shapes.md,
                md,
                enabled = false,
                colors = ButtonDefaults.buttonColors(disabledContainerColor = c.surfaceSunken, disabledContentColor = c.textDisabled),
            ) { Text(T(R.string.sg_btn_disabled)) }
            Button(onClick = {}, md, shape = KbTheme.shapes.md) {
                CircularProgressIndicator(progress = { 0.7f }, Modifier.size(KbTheme.size.iconSm), color = c.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(KbTheme.space.s2))
                Text(T(R.string.sg_btn_loading))
            }
            OutlinedButton(
                onClick = {},
                shape = KbTheme.shapes.md,
                Modifier.heightIn(min = KbTheme.size.controlSm),
                border = BorderStroke(1.dp, c.borderStrong),
                contentPadding = ButtonDefaults.ContentPadding,
            ) { Text(T(R.string.sg_btn_small)) }
        }
        Button(onClick = {}, Modifier.fillMaxWidth().height(KbTheme.size.gradeButton), shape = KbTheme.shapes.md) {
            Text(T(R.string.sg_btn_show_answer), style = KbTheme.type.titleSm)
        }
    }
    SgGroup(R.string.sg_group_icon_buttons) {
        var on by remember { mutableStateOf(true) }
        val play = T(R.string.sg_icon_play)
        val flag = T(R.string.sg_icon_flag)
        val more = T(R.string.sg_icon_more)
        Row(horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2)) {
            IconButton(onClick = {}, Modifier.semantics { contentDescription = play }) { Glyph("▶") }
            FilledTonalIconButton(onClick = {}, Modifier.semantics { contentDescription = more }) { Glyph("⋯") }
            OutlinedIconButton(onClick = {}, Modifier.semantics { contentDescription = play }, border = BorderStroke(1.dp, c.borderStrong)) {
                Glyph("▶")
            }
            IconToggleButton(checked = on, onCheckedChange = { on = it }, Modifier.semantics { contentDescription = flag }) {
                Glyph("⚑", if (on) c.primary else c.textMuted)
            }
        }
    }
    SgGroup(R.string.sg_group_segmented) {
        Segmented(listOf(R.string.sg_seg_cards, R.string.sg_seg_notes))
        Segmented(listOf(R.string.sg_range_today, R.string.sg_range_week, R.string.sg_range_month, R.string.sg_range_year))
    }
    SgGroup(R.string.sg_group_tabs) {
        var tab by remember { mutableIntStateOf(0) }
        TabRow(selectedTabIndex = tab, containerColor = c.surface, contentColor = c.primary) {
            listOf(R.string.sg_tab_senses to "4", R.string.sg_tab_kanji to "2", R.string.sg_tab_examples to "12")
                .forEachIndexed { i, (label, count) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text("${T(label)} $count", style = KbTheme.type.label) },
                        unselectedContentColor = c.textMuted,
                    )
                }
        }
    }
    SgGroup(R.string.sg_group_chips) {
        var due by remember { mutableStateOf(true) }
        var audio by remember { mutableStateOf(false) }
        SgFlow {
            FilterChip(
                selected = due,
                onClick = { due = !due },
                label = { Text(T(R.string.sg_chip_due)) },
                leadingIcon = if (due) ({ Glyph("✓", size = KbTheme.type.caption) }) else null,
            )
            FilterChip(selected = audio, onClick = { audio = !audio }, label = { Text(T(R.string.sg_chip_audio)) })
            DeckChip(KbDeckColor.Matcha)
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(T(R.string.sg_chip_tag)) },
                trailingIcon = { Glyph("×", size = KbTheme.type.label) },
            )
        }
        SgFlow { KbCardState.entries.forEach { StateChip(it) } }
    }
}

@Composable
private fun Segmented(options: List<Int>) {
    var selected by remember { mutableIntStateOf(0) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = selected == i,
                onClick = { selected = i },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = KbTheme.colors.primaryContainer,
                    activeContentColor = KbTheme.colors.onPrimaryContainer,
                    activeBorderColor = KbTheme.colors.borderStrong,
                    inactiveBorderColor = KbTheme.colors.borderStrong,
                ),
            ) { Text(T(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A text glyph standing in for an icon (the icon set is UI-APP's choice). */
@Composable
private fun Glyph(g: String, color: Color = KbTheme.colors.text, size: androidx.compose.ui.text.TextStyle = KbTheme.type.titleSm) {
    Text(g, style = size, color = color)
}

/** State chip: colour + glyph + word. */
@Composable
fun StateChip(s: KbCardState) {
    val tone = KbTheme.colors.state(s)
    Box(
        Modifier.heightIn(min = 32.dp).background(tone.container, KbTheme.shapes.full).padding(horizontal = KbTheme.space.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text("${s.glyph} ${stateLabel(s)}", style = KbTheme.type.label, color = tone.content)
    }
}

/** Deck chip: dot + name (the colour is never alone). */
@Composable
fun DeckChip(d: KbDeckColor) {
    val tone = KbTheme.colors.deck(d)
    Row(
        Modifier.heightIn(min = 32.dp).border(1.dp, KbTheme.colors.border, KbTheme.shapes.full).padding(horizontal = KbTheme.space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2),
    ) {
        Box(Modifier.size(10.dp).background(tone.content, CircleShape))
        Text(T(R.string.sg_deck_core), style = KbTheme.type.label, color = KbTheme.colors.text)
    }
}

// ---- surfaces, rows, inputs --------------------------------------------------------------------------------------

@Composable
fun ListsPage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_card_surface) {
        SgFlow {
            listOf(
                Triple(R.string.sg_surface, c.surface, 0.dp),
                Triple(R.string.sg_surface_sunken, c.surfaceSunken, 0.dp),
                Triple(R.string.sg_surface_raised, c.surfaceRaised, KbTheme.elevation.level1),
            ).forEach { (label, color, e) ->
                Surface(
                    Modifier.size(width = 112.dp, height = 64.dp),
                    shape = KbTheme.shapes.lg,
                    color = color,
                    shadowElevation = e,
                    border = BorderStroke(1.dp, c.border),
                ) { Box(Modifier.padding(KbTheme.space.s3)) { Text(T(label), style = KbTheme.type.label, color = c.text) } }
            }
        }
        Box(Modifier.fillMaxWidth().background(c.surfaceSunken, KbTheme.shapes.md).padding(KbTheme.space.s3)) {
            Text(T(R.string.sg_mnemonic), style = KbTheme.type.bodySm, color = c.text)
        }
    }
    SgGroup(R.string.sg_group_list_row) {
        Surface(shape = KbTheme.shapes.lg, color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(KbTheme.space.s3), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2)) {
                            Text(T(R.string.sg_row_title), style = KbTheme.type.titleSm.merge(KbTheme.type.jpBody.copy(fontSize = KbTheme.type.titleSm.fontSize, lineHeight = KbTheme.type.titleSm.lineHeight)), color = c.text)
                            Text(T(R.string.sg_row_reading), style = KbTheme.type.bodySm.merge(KbTheme.type.reading.copy(fontSize = KbTheme.type.bodySm.fontSize, lineHeight = KbTheme.type.bodySm.lineHeight)), color = c.textMuted)
                        }
                        Text(T(R.string.sg_row_supporting), style = KbTheme.type.bodySm, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(T(R.string.sg_row_meta), style = KbTheme.type.caption, color = c.textMuted)
                    }
                    Spacer(Modifier.width(KbTheme.space.s2))
                    StateChip(KbCardState.Review)
                }
                HorizontalDivider(color = c.border)
                Row(
                    Modifier.fillMaxWidth().background(c.surfaceSelected).heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(56.dp).background(c.primary))
                    Box(Modifier.padding(start = KbTheme.space.s3).size(12.dp).background(c.deck(KbDeckColor.Ai).content, CircleShape))
                    Column(Modifier.weight(1f).padding(KbTheme.space.s3)) {
                        Text(T(R.string.sg_row_deck_title), style = KbTheme.type.titleSm, color = c.text)
                        Text(T(R.string.sg_row_deck_counts), style = KbTheme.type.bodySm, color = c.textMuted)
                    }
                    Text("›", Modifier.padding(end = KbTheme.space.s4), style = KbTheme.type.title, color = c.textMuted)
                }
                HorizontalDivider(color = c.border)
                var on by remember { mutableStateOf(true) }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(KbTheme.space.s3), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T(R.string.sg_row_setting_title), style = KbTheme.type.titleSm, color = c.text)
                        Text(T(R.string.sg_row_setting_supporting), style = KbTheme.type.bodySm, color = c.textMuted)
                    }
                    Switch(checked = on, onCheckedChange = { on = it })
                }
            }
        }
    }
    SgGroup(R.string.sg_group_input) {
        OutlinedTextField(
            value = T(R.string.sg_input_value),
            onValueChange = {},
            Modifier.fillMaxWidth(),
            label = { Text(T(R.string.sg_input_label)) },
            supportingText = { Text(T(R.string.sg_input_hint)) },
            shape = KbTheme.shapes.sm,
        )
        OutlinedTextField(
            value = T(R.string.sg_input_error_value),
            onValueChange = {},
            Modifier.fillMaxWidth(),
            label = { Text(T(R.string.sg_input_error_label)) },
            isError = true,
            supportingText = { Text("⚠ " + T(R.string.sg_input_error)) },
            shape = KbTheme.shapes.sm,
        )
        OutlinedTextField(
            value = "",
            onValueChange = {},
            Modifier.fillMaxWidth(),
            placeholder = { Text(T(R.string.sg_input_search)) },
            leadingIcon = { Glyph("⌕", c.textMuted) },
            shape = KbTheme.shapes.sm,
        )
        OutlinedTextField(
            value = T(R.string.sg_input_value),
            onValueChange = {},
            Modifier.fillMaxWidth(),
            enabled = false,
            label = { Text(T(R.string.sg_input_disabled)) },
            shape = KbTheme.shapes.sm,
        )
    }
}

// ---- dialogs, toasts, empty, loading -------------------------------------------------------------------------------

@Composable
fun FeedbackPage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_dialog) {
        // Drawn inline (a real Dialog is a separate window that a screenshot of this page would not show).
        Box(Modifier.fillMaxWidth().background(c.scrim, KbTheme.shapes.lg).padding(KbTheme.space.s4), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 400.dp), shape = KbTheme.shapes.xl, color = c.surfaceRaised, shadowElevation = KbTheme.elevation.level3) {
                Column(Modifier.padding(KbTheme.space.s6), verticalArrangement = Arrangement.spacedBy(KbTheme.space.s3)) {
                    Text(T(R.string.sg_dialog_title), style = KbTheme.type.title, color = c.text)
                    Text(T(R.string.sg_dialog_body), style = KbTheme.type.body, color = c.textMuted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2, Alignment.End)) {
                        TextButton(onClick = {}, shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_cancel)) }
                        Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger), shape = KbTheme.shapes.md) {
                            Text(T(R.string.sg_btn_delete))
                        }
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = KbTheme.shapes.sheet, color = c.surfaceRaised, shadowElevation = KbTheme.elevation.level3) {
            Column(Modifier.padding(KbTheme.space.s4), verticalArrangement = Arrangement.spacedBy(KbTheme.space.s3)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp).background(c.borderStrong, KbTheme.shapes.full))
                Text(T(R.string.sg_sheet_title), style = KbTheme.type.title, color = c.text)
                OutlinedTextField(T(R.string.sg_input_value), {}, Modifier.fillMaxWidth(), label = { Text(T(R.string.sg_input_label)) }, shape = KbTheme.shapes.sm)
                Button(onClick = {}, Modifier.fillMaxWidth().heightIn(min = KbTheme.size.control), shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_create)) }
                TextButton(onClick = {}, Modifier.fillMaxWidth(), shape = KbTheme.shapes.md) { Text(T(R.string.sg_btn_cancel)) }
            }
        }
    }
    SgGroup(R.string.sg_group_toast) {
        Toast(KbFeedback.Info, R.string.sg_toast_info, R.string.sg_undo)
        Toast(KbFeedback.Success, R.string.sg_toast_success, R.string.sg_undo)
        Toast(KbFeedback.Warning, R.string.sg_toast_warning, null)
        Toast(KbFeedback.Danger, R.string.sg_toast_error, R.string.sg_retry)
    }
    SgGroup(R.string.sg_group_empty) {
        EmptyState(enso = true, R.string.sg_empty_done_title, R.string.sg_empty_done_body, R.string.sg_empty_done_action, error = false)
        EmptyState(enso = false, R.string.sg_empty_decks_title, R.string.sg_empty_decks_body, R.string.sg_empty_decks_action, error = false)
        EmptyState(enso = false, R.string.sg_error_title, R.string.sg_error_body, R.string.sg_retry, error = true)
    }
    SgGroup(R.string.sg_group_loading) {
        // Static skeletons (the real pulse stops under reduced motion; a still frame is what a screenshot shows).
        repeat(2) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s3)) {
                Box(Modifier.size(12.dp).background(c.surfaceSunken, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(KbTheme.space.s1)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(16.dp).background(c.surfaceSunken, KbTheme.shapes.xs))
                    Box(Modifier.fillMaxWidth(0.8f).height(12.dp).background(c.surfaceSunken, KbTheme.shapes.xs))
                }
            }
        }
        CircularProgressIndicator(progress = { 0.3f }, Modifier.size(KbTheme.size.iconLg), color = c.primary, strokeWidth = 2.dp)
    }
    SgGroup(R.string.sg_group_progress) {
        Progress(12f / 85f, T(R.string.sg_progress_count))
        Progress(0.64f, T(R.string.sg_progress_import, 64))
    }
}

@Composable
private fun Progress(fraction: Float, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s3)) {
        LinearProgressIndicator(
            progress = { fraction },
            Modifier.weight(1f).height(4.dp),
            color = KbTheme.colors.primary,
            trackColor = KbTheme.colors.surfaceSunken,
            drawStopIndicator = {},
        )
        Text(label, style = KbTheme.type.caption, color = KbTheme.colors.textMuted)
    }
}

@Composable
private fun Toast(kind: KbFeedback, message: Int, action: Int?) {
    val tone = KbTheme.colors.feedback(kind)
    Surface(Modifier.fillMaxWidth(), shape = KbTheme.shapes.md, color = tone.container, contentColor = tone.content, shadowElevation = KbTheme.elevation.level2) {
        Row(Modifier.padding(start = KbTheme.space.s4, end = KbTheme.space.s1), verticalAlignment = Alignment.CenterVertically) {
            val glyph = when (kind) {
                KbFeedback.Info -> "ℹ"
                KbFeedback.Success -> "✓"
                KbFeedback.Warning -> "⚠"
                KbFeedback.Danger -> "✕"
            }
            Text("$glyph ${T(message)}", Modifier.weight(1f).padding(vertical = KbTheme.space.s3), style = KbTheme.type.bodySm)
            if (action != null) TextButton(onClick = {}, shape = KbTheme.shapes.md) { Text(T(action), color = tone.content, style = KbTheme.type.label) }
            if (kind == KbFeedback.Danger) {
                val dismiss = T(R.string.sg_dismiss)
                IconButton(onClick = {}, Modifier.semantics { contentDescription = dismiss }) { Glyph("×", tone.content) }
            }
        }
    }
}

@Composable
private fun EmptyState(enso: Boolean, title: Int, body: Int, action: Int, error: Boolean) {
    val c = KbTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface, KbTheme.shapes.lg).border(1.dp, c.border, KbTheme.shapes.lg).padding(KbTheme.space.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KbTheme.space.s2),
    ) {
        if (enso) {
            Enso(Modifier.size(64.dp))
        } else {
            val tone = if (error) c.feedback(KbFeedback.Danger) else c.feedback(KbFeedback.Info)
            Box(Modifier.size(48.dp).background(tone.container, CircleShape), contentAlignment = Alignment.Center) {
                Text(if (error) "!" else "＋", style = KbTheme.type.title, color = tone.content)
            }
        }
        Text(T(title), style = KbTheme.type.title, color = c.text)
        Text(T(body), style = KbTheme.type.body, color = c.textMuted)
        if (error) {
            OutlinedButton(onClick = {}, border = BorderStroke(1.dp, c.borderStrong), shape = KbTheme.shapes.md) { Text(T(action)) }
        } else {
            FilledTonalButton(onClick = {}, shape = KbTheme.shapes.md) { Text(T(action)) }
        }
    }
}

/** The vermilion enso, reserved for "review finished": an open brush circle. */
@Composable
fun Enso(modifier: Modifier) {
    val brand = KbTheme.colors.brand
    Canvas(modifier) {
        val w = size.minDimension * 0.09f
        drawArc(brand, startAngle = -70f, sweepAngle = 320f, useCenter = false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

// ---- review, pitch, navigation -----------------------------------------------------------------------------------

@Composable
fun ReviewPage() {
    val c = KbTheme.colors
    SgGroup(R.string.sg_group_grade_bar) {
        GradeBar(columns = 4, keyHints = true)
        Box(Modifier.widthIn(max = 280.dp)) { GradeBar(columns = 2, keyHints = false) }
    }
    SgGroup(R.string.sg_group_pitch_accent) {
        SgFlow {
            PitchAccent(T(R.string.sg_pitch_word), T(R.string.sg_pitch_word_kanji), KbPitch.Atamadaka, drop = 1)
            PitchAccent(T(R.string.sg_pitch_word2), T(R.string.sg_pitch_word2_kanji), KbPitch.Heiban, drop = 0)
        }
    }
    SgGroup(R.string.sg_group_kbd) {
        SgFlow { listOf("Space", "1", "2", "3", "4", "U", "E", "?").forEach { Kbd(it) } }
        Text(T(R.string.sg_kbd_hint), style = KbTheme.type.caption, color = c.textMuted)
    }
    SgGroup(R.string.sg_group_shell) {
        val items = listOf(
            R.string.sg_nav_today to "◉",
            R.string.sg_nav_review to "▣",
            R.string.sg_nav_search to "⌕",
            R.string.sg_nav_decks to "≡",
            R.string.sg_nav_settings to "⚙",
        )
        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            items.forEachIndexed { i, (label, glyph) ->
                NavigationBarItem(selected = i == 0, onClick = {}, icon = { Glyph(glyph) }, label = { Text(T(label), maxLines = 1) })
            }
        }
        Row(Modifier.height(320.dp)) {
            NavigationRail(containerColor = c.surface, header = { Text("言", style = KbTheme.type.title.copy(fontFamily = KbTheme.type.jpHero.fontFamily), color = c.brand) }) {
                items.forEachIndexed { i, (label, glyph) ->
                    NavigationRailItem(selected = i == 1, onClick = {}, icon = { Glyph(glyph) }, label = { Text(T(label), maxLines = 1) })
                }
            }
            Box(Modifier.weight(1f).height(320.dp).background(c.bg))
        }
    }
}

@Composable
private fun GradeBar(columns: Int, keyHints: Boolean) {
    val grades = KbGrade.entries
    val intervals = listOf(R.string.sg_interval_again, R.string.sg_interval_hard, R.string.sg_interval_good, R.string.sg_interval_easy)
    Column(verticalArrangement = Arrangement.spacedBy(KbTheme.space.s2)) {
        grades.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2)) {
                row.forEach { g ->
                    val i = grades.indexOf(g)
                    GradeButton(g, T(intervals[i]), if (keyHints) "${i + 1}" else null, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Tonal container with a 3 dp top rule in the grade colour; Good (the default) is fully outlined. */
@Composable
private fun GradeButton(g: KbGrade, interval: String, key: String?, modifier: Modifier) {
    val tone = KbTheme.colors.grade(g)
    val shape = KbTheme.shapes.md
    Box(
        modifier.height(KbTheme.size.gradeButton)
            .background(tone.container, shape)
            .then(if (g == KbGrade.Good) Modifier.border(2.dp, tone.content, shape) else Modifier),
    ) {
        Canvas(Modifier.fillMaxWidth().height(3.dp).padding(horizontal = KbTheme.space.s3)) {
            drawRect(tone.content)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(gradeLabel(g), style = KbTheme.type.label, color = tone.content)
            Text(interval, style = KbTheme.type.caption, color = KbTheme.colors.text)
        }
        if (key != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(KbTheme.space.s1)) { Kbd(key, small = true) }
        }
    }
}

@Composable
fun Kbd(key: String, small: Boolean = false) {
    val c = KbTheme.colors
    Box(
        Modifier.background(c.surfaceSunken, KbTheme.shapes.xs)
            .border(1.dp, c.borderStrong, KbTheme.shapes.xs)
            .padding(horizontal = if (small) KbTheme.space.s1 else KbTheme.space.s2, vertical = KbTheme.space.s0_5),
    ) {
        Text(key, style = KbTheme.type.caption, color = c.text)
    }
}

/**
 * Morae with an overline where the pitch is high and a tick where it drops after mora [drop] (0 = heiban, no drop);
 * the pattern number and name sit next to it.
 */
@Composable
private fun PitchAccent(kana: String, kanji: String, pattern: KbPitch, drop: Int) {
    val c = KbTheme.colors
    val color = c.pitch(pattern)
    val morae = kana.map { it.toString() }
    val high = morae.indices.map { i -> if (drop == 0) i > 0 else if (drop == 1) i == 0 else i in 1 until drop }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s3)) {
        Text(kanji, style = KbTheme.type.title.merge(KbTheme.type.jpBody.copy(fontSize = KbTheme.type.title.fontSize)), color = c.text)
        Row {
            morae.forEachIndexed { i, m ->
                Box(Modifier.padding(top = 4.dp)) {
                    Text(m, Modifier.padding(horizontal = 1.dp, vertical = 2.dp), style = KbTheme.type.reading, color = c.text)
                    Canvas(Modifier.matchParentSize()) {
                        val s = 2.dp.toPx()
                        if (high[i]) drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), s)
                        if (drop != 0 && i == drop - 1) drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height * 0.35f), s)
                    }
                }
            }
        }
        Text(T(pitchLabel(pattern)), style = KbTheme.type.caption, color = c.textMuted)
    }
}
