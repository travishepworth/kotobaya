package kotobaya.android.review.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.review.ReviewAction
import kotobaya.android.review.ReviewActionsUiState
import kotobaya.android.review.ReviewIntents
import kotobaya.android.review.ReviewMeta
import kotobaya.android.review.ReviewPrefs
import kotobaya.android.review.ReviewState
import kotobaya.android.review.ReviewUiState
import kotobaya.android.review.SessionStats
import kotobaya.android.review.ShownCard
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonSize
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbEmptyTone
import kotobaya.android.ui.components.KbEnso
import kotobaya.android.ui.components.KbGlyphButton
import kotobaya.android.ui.components.KbIconButton
import kotobaya.android.ui.components.KbIconVariant
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbKey
import kotobaya.android.ui.components.KbMenu
import kotobaya.android.ui.components.KbMenuItem
import kotobaya.android.ui.components.KbSheet
import kotobaya.android.ui.components.KbSkeleton
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.components.hasHardwareKeyboard
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbMotion
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.grade
import kotobaya.android.ui.theme.kbDistance
import kotobaya.android.ui.theme.kbTween
import kotobaya.contracts.fsrs.Button
import kotobaya.contracts.render.CardSide
import kotobaya.sync.client.mint.MintRefusal

@StringRes
private fun blockedMessage(r: MintRefusal): Int = when (r) {
    MintRefusal.NotPaired -> R.string.review_blocked_not_paired
    MintRefusal.RebuildPending -> R.string.review_blocked_rebuild
    is MintRefusal.ClockGuard, is MintRefusal.ClockFarFromServer -> R.string.review_blocked_clock
    else -> R.string.review_blocked_other
}

/** Widest the card and the grade bar get (DESIGN §5.1: 640 dp on tablets and foldables). */
private val CARD_MAX = 640.dp

/**
 * The review screen (DESIGN §5.1), the product: a slim header, the card filling the height, and the action area in
 * the thumb zone. Before the reveal the action area holds one big Show answer; after it, the grade bar takes exactly
 * the same place, so neither the card nor the buttons move. With a hardware keyboard: Space/Enter shows the answer,
 * 1–4 (1–5) grade, U undoes, E edits.
 */
@Composable
fun ReviewScreen(state: ReviewUiState, intents: ReviewIntents) {
    val c = KbTheme.colors
    if (!state.available) {
        Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
            KbEmptyState(T(R.string.review_not_ready_title), body = T(R.string.review_not_ready), icon = KbIcons.Sync, page = true)
        }
        return
    }
    val review = state.review
    val shown: ShownCard? = when (review) {
        is ReviewState.Showing -> review.card
        is ReviewState.Blocked -> review.card
        else -> null
    }
    val side = (review as? ReviewState.Showing)?.side ?: CardSide.FRONT
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // AND-BACK (ADR-030): back undoes the last answer once, then leaves; sheets (their own dialog) close first.
    val leave = LocalReviewExit.current
    BackHandler(enabled = leave != null) { if (!intents.backUndo()) leave?.invoke() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && handleKey(e.key, review, state.prefs, intents) },
    ) {
        val wide = maxWidth >= KbTheme.breakpoint.medium
        val gutter = if (wide) 24.dp else 12.dp
        Column(Modifier.fillMaxSize()) {
            ReviewHeader(state, shown, intents, Modifier.padding(horizontal = if (wide) 16.dp else 4.dp))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = gutter),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.syncPrompt) {
                    KbBanner(
                        T(R.string.review_sync_prompt, state.meta.pendingOps),
                        Modifier.widthIn(max = CARD_MAX).align(Alignment.CenterHorizontally),
                        action = if (state.canSync) {
                            { KbButton(T(R.string.review_sync_now), intents::syncNow, variant = KbButtonVariant.Quiet) }
                        } else {
                            null
                        },
                    )
                }
                if (review is ReviewState.Blocked) {
                    KbBanner(T(blockedMessage(review.reason)), Modifier.widthIn(max = CARD_MAX).align(Alignment.CenterHorizontally), kind = KbFeedback.Warning)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter).padding(top = 8.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.widthIn(max = CARD_MAX).fillMaxWidth().fillMaxHeight()) {
                    when (review) {
                        ReviewState.Loading -> {
                            LoadingCard(Modifier.weight(1f))
                            ActionArea(side = CardSide.FRONT, card = null, prefs = state.prefs, keyHints = false, intents = intents, enabled = false)
                        }
                        ReviewState.Empty -> Finished(null, Modifier.weight(1f), state.prefs, null, intents, state.meta)
                        ReviewState.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            KbEmptyState(
                                T(R.string.review_error_title),
                                body = T(R.string.review_error_body),
                                icon = KbIcons.Info,
                                tone = KbEmptyTone.Neutral,
                                page = true,
                                action = { KbButton(T(R.string.review_error_retry), intents::retry) },
                            )
                        }
                        is ReviewState.Summary -> Finished(review.stats, Modifier.weight(1f), state.prefs, null, intents, state.meta)
                        is ReviewState.DeckDone -> Finished(review.stats, Modifier.weight(1f), state.prefs, review, intents)
                        is ReviewState.Waiting -> Finished(review.stats, Modifier.weight(1f), state.prefs, null, intents, state.meta, waiting = review)
                        is ReviewState.Blocked, is ReviewState.Showing -> {
                            val card = shown
                            if (card == null) {
                                Box(Modifier.weight(1f))
                            } else {
                                Box(Modifier.weight(1f)) {
                                    ReviewCard(card, side, state, intents, Modifier.fillMaxSize())
                                    ActionToast(state.actions, card.cardId, intents, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                                }
                                ActionArea(side, card, state.prefs, hasHardwareKeyboard() && wide, intents, enabled = review is ReviewState.Showing)
                            }
                        }
                    }
                }
            }
        }
    }
    state.actions.editing?.let { note -> shown?.let { EditSheet(note, it.cardId, intents) } }
}

/** Hardware-keyboard shortcuts; returns true when handled. */
private fun handleKey(key: Key, review: ReviewState, prefs: ReviewPrefs, intents: ReviewIntents): Boolean {
    val showing = review as? ReviewState.Showing ?: return false
    return when {
        (key == Key.Spacebar || key == Key.Enter) && showing.side == CardSide.FRONT -> { intents.flip(); true }
        key == Key.U -> { intents.undo(); true }
        key == Key.E -> { intents.edit(showing.card.cardId); true }
        key == Key.S -> { intents.perform(showing.card.cardId, ReviewAction.SUSPEND); true }
        key == Key.N -> { intents.perform(showing.card.cardId, ReviewAction.NEVER_FORGET); true }
        key == Key.B -> { intents.perform(showing.card.cardId, ReviewAction.BURY); true }
        showing.side == CardSide.BACK -> {
            val digit = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).indexOf(key)
            val b = prefs.buttons.getOrNull(digit)
            if (digit >= 0 && b != null) {
                intents.answer(showing.card.token, b)
                true
            } else {
                false
            }
        }
        else -> false
    }
}

/** Slim header: session count, furigana toggle, undo and the overflow menu. */
@Composable
private fun ReviewHeader(state: ReviewUiState, card: ShownCard?, intents: ReviewIntents, modifier: Modifier) {
    val c = KbTheme.colors
    val meta = state.meta
    var menu by remember { mutableStateOf(false) }
    var flags by remember { mutableStateOf(false) }
    val exit = LocalReviewExit.current
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (exit != null) KbIconButton(KbIcons.Close, T(R.string.review_close), exit)
        Row(Modifier.weight(1f).padding(start = if (exit != null) 4.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(T(R.string.nav_review), style = KbTheme.type.titleSm, color = c.text)
            if (meta.answered > 0) {
                Text(
                    T(R.string.review_reviewed_count, meta.answered),
                    style = KbTheme.type.caption,
                    color = c.onSuccessContainer,
                    modifier = Modifier.clip(RoundedCornerShape(KbRadius.sm)).background(c.successContainer).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        KbGlyphButton("ふ", T(R.string.review_furigana), intents::toggleFurigana, selected = meta.furigana, glyphStyle = KbTheme.type.titleSm.copy(localeList = kotobaya.android.ui.theme.JapaneseLocale))
        // On the finished page with nothing to undo the button would be a dead control: hide it.
        val finished = state.review == ReviewState.Empty || state.review is ReviewState.Summary || state.review is ReviewState.DeckDone ||
            state.review is ReviewState.Waiting
        if (!finished || meta.canUndo) KbIconButton(KbIcons.Undo, T(R.string.review_undo), intents::undo, enabled = meta.canUndo)
        if (card != null) {
            Box {
                KbIconButton(KbIcons.More, T(R.string.review_actions_menu), { intents.openActions(card.cardId); menu = true })
                val mine = state.actions.cardId == card.cardId
                val s = state.actions.card.takeIf { mine }
                val items = buildList {
                    add(KbMenuItem(T(R.string.review_action_edit), KbIcons.Edit) { intents.edit(card.cardId) })
                    add(KbMenuItem(T(R.string.review_action_flag), KbIcons.Flag) { flags = true })
                    add(KbMenuItem(T(if (mine && state.actions.buried) R.string.review_action_unbury else R.string.review_action_bury)) { intents.perform(card.cardId, ReviewAction.BURY) })
                    add(KbMenuItem(T(if (s?.suspended == true) R.string.review_action_unsuspend else R.string.review_action_suspend)) { intents.perform(card.cardId, ReviewAction.SUSPEND) })
                    if (s?.entrySeq != null) {
                        add(
                            KbMenuItem(T(if (s.wordOverride == "never_forget") R.string.review_action_clear_never_forget else R.string.review_action_never_forget)) {
                                intents.perform(card.cardId, ReviewAction.NEVER_FORGET)
                            },
                        )
                        add(
                            KbMenuItem(T(if (s.wordOverride == "blacklisted") R.string.review_action_clear_blacklist else R.string.review_action_blacklist), danger = true) {
                                intents.perform(card.cardId, ReviewAction.BLACKLIST)
                            },
                        )
                    }
                }
                KbMenu(menu, { menu = false }, items)
                KbMenu(
                    flags,
                    { flags = false },
                    (0..7).map { f ->
                        KbMenuItem(if (f == 0) T(R.string.review_flag_none) else T(R.string.review_flag_colour, f)) { intents.flag(card.cardId, f) }
                    },
                )
            }
        }
    }
}

/**
 * The card: a raised surface (radius lg, elevation 1) filling the height. A new card fades in and settles 16 dp from
 * the right; the reveal is a short emphasized fade of the card body (the headword stays where it was). Tapping the
 * front reveals; on the back a horizontal swipe grades with the user's swipe mapping.
 */
@Composable
private fun ReviewCard(card: ShownCard, side: CardSide, state: ReviewUiState, intents: ReviewIntents, modifier: Modifier) {
    val c = KbTheme.colors
    val density = LocalDensity.current
    val reduced = KbTheme.reducedMotion
    val enter = remember { Animatable(1f) }
    var lastToken by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(card.token) {
        if (lastToken != null && lastToken != card.token) {
            enter.snapTo(0f)
            enter.animateTo(1f, kbTween(KbMotion.BASE, KbMotion.decelerate))
        }
        lastToken = card.token
    }
    val reveal = remember { Animatable(1f) }
    LaunchedEffect(card.token, side) {
        if (side == CardSide.BACK) {
            reveal.snapTo(0.55f)
            reveal.animateTo(1f, kbTween(KbMotion.BASE, KbMotion.emphasized))
        }
    }
    val slide = with(density) { kbDistance(KbTheme.motion.distanceLg, reduced).toPx() }
    val rendered = if (side == CardSide.FRONT) card.front else card.back
    val shape = KbTheme.shapes.lg
    var dragged by remember { mutableStateOf(0f) }
    Box(
        modifier
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * slide
            }
            .shadow(1.dp, shape, ambientColor = c.scrim, spotColor = c.scrim)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = side == CardSide.FRONT, role = Role.Button, onClick = intents::flip)
            .pointerInput(card.token, side, state.prefs) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { _, dx -> dragged += dx },
                    onDragEnd = {
                        if (side == CardSide.BACK && kotlin.math.abs(dragged) > SWIPE_PX) {
                            state.prefs.swipeButton(right = dragged > 0)?.let { b -> intents.answer(card.token, b) }
                        }
                    },
                )
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // KIND-CHIP-OVERLAP: the chip row is in normal flow above the content, so nothing can sit under a chip
            CardHeaderRow(
                isNew = card.kind == kotobaya.contracts.queue.QueueItemKind.NEW,
                kindRes = kindLabelRes(card.noteType),
                hasAudio = rendered.audio.isNotEmpty(),
                audioMissing = state.audioMissing,
                onPlayAudio = { intents.playAudio(rendered.audio.first()) },
            )
            CardContent(rendered, intents::mediaFile, Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = reveal.value }.testTag(TAG_CARD_CONTENT))
            // AND-REVIEW-INFO: example words, deck chips and the dictionary button, from the prefetched card
            if (side == CardSide.BACK) BackPanel(card.extras, intents, Modifier.graphicsLayer { alpha = reveal.value })
        }
        card.noExamplePattern?.let { pattern ->
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                    .clip(RoundedCornerShape(KbRadius.md)).background(c.surfaceSunken).padding(12.dp),
            ) {
                Text(T(R.string.review_no_example_sentence), style = KbTheme.type.bodySm, color = c.text)
                if (pattern.isNotEmpty()) Text(T(R.string.review_no_example_pattern, pattern), style = KbTheme.type.bodySm.copy(localeList = kotobaya.android.ui.theme.JapaneseLocale), color = c.textMuted)
            }
        }
    }
}

internal const val TAG_CARD_CONTENT = "card-content"
internal const val TAG_CARD_HEADER = "card-header"
internal const val TAG_KIND_CHIP = "card-kind-chip"

/**
 * The tag row above the card content: "New" (start), the note type (centre, same wording as web) and the audio button
 * (end). A Row in the card's Column, so its height is reserved and the content starts below it.
 */
@Composable
internal fun CardHeaderRow(isNew: Boolean, kindRes: Int?, hasAudio: Boolean, audioMissing: Boolean, onPlayAudio: () -> Unit) {
    if (!isNew && kindRes == null && !hasAudio) return
    val c = KbTheme.colors
    Row(Modifier.fillMaxWidth().padding(8.dp).testTag(TAG_CARD_HEADER), verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
            if (isNew) {
                kotobaya.android.ui.components.KbStateChip(kotobaya.android.ui.theme.KbCardState.New, T(R.string.review_new_badge))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            if (kindRes != null) kotobaya.android.ui.components.KbTagChip(T(kindRes), Modifier.testTag(TAG_KIND_CHIP))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            if (hasAudio) {
                KbIconButton(KbIcons.Audio, T(R.string.review_play_audio), onPlayAudio, variant = KbIconVariant.Tonal)
                if (audioMissing) {
                    Text(
                        T(R.string.review_no_audio),
                        style = KbTheme.type.caption,
                        color = c.textMuted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(max = 180.dp).padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** A skeleton card of the real size, so nothing shifts when the first card arrives. */
@Composable
private fun LoadingCard(modifier: Modifier) {
    val c = KbTheme.colors
    val shape = KbTheme.shapes.lg
    Box(modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.border, shape).semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            KbSkeleton(Modifier.size(width = 140.dp, height = 44.dp), radius = KbRadius.sm)
            KbSkeleton(Modifier.size(width = 96.dp, height = 18.dp))
        }
    }
}

/**
 * The action area: Show answer and the grade bar share one box whose size is the grade bar's, so revealing never
 * moves anything. They cross-fade over `base`.
 */
@Composable
private fun ActionArea(side: CardSide, card: ShownCard?, prefs: ReviewPrefs, keyHints: Boolean, intents: ReviewIntents, enabled: Boolean) {
    val back = side == CardSide.BACK && card != null
    val gradeAlpha by animateFloatAsState(if (back) 1f else 0f, kbTween(KbMotion.BASE))
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp).navigationBarsPadding()) {
        GradeBar(
            buttons = prefs.buttons,
            previews = card?.previews,
            keyHints = keyHints,
            enabled = back && enabled,
            onGrade = { b -> card?.let { intents.answer(it.token, b) } },
            modifier = Modifier.graphicsLayer { alpha = gradeAlpha },
        )
        if (gradeAlpha < 1f) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - gradeAlpha }, contentAlignment = Alignment.BottomCenter) {
                KbButton(
                    T(R.string.review_show_answer),
                    intents::flip,
                    Modifier.fillMaxWidth().heightIn(min = GRADE_HEIGHT),
                    size = KbButtonSize.Lg,
                    enabled = enabled && !back,
                    trailing = if (keyHints) {
                        { KbKey(T(R.string.review_key_space)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The last toolbar action's result as a toast above the actions (Undo when possible); fades after 5 s. */
@Composable
private fun ActionToast(actions: ReviewActionsUiState, cardId: String, intents: ReviewIntents, modifier: Modifier) {
    val c = KbTheme.colors
    val message = actions.message
    var hidden by remember(actions) { mutableStateOf(false) }
    LaunchedEffect(actions) {
        delay(TOAST_MS)
        hidden = true
    }
    AnimatedVisibility(message != null && !hidden, modifier, enter = fadeIn(kbTween(KbMotion.BASE)), exit = fadeOut(kbTween(KbMotion.FAST))) {
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(3.dp, RoundedCornerShape(KbRadius.md), ambientColor = c.scrim, spotColor = c.scrim)
                .clip(RoundedCornerShape(KbRadius.md))
                .background(c.text)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (message != null) Text(T(message), style = KbTheme.type.bodySm, color = c.bg)
                if (actions.emptied > 0) Text(T(R.string.review_edit_empty_front, actions.emptied), style = KbTheme.type.caption, color = c.bg)
            }
            if (actions.undoable) {
                Text(
                    T(R.string.review_action_undo),
                    style = KbTheme.type.label,
                    color = c.bg,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(KbRadius.sm))
                        .clickable(role = Role.Button) { intents.undoAction(cardId) }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
            }
            KbIconButton(KbIcons.Close, T(R.string.review_dismiss), { hidden = true }, tint = c.bg)
        }
    }
}

/**
 * The finished state (DESIGN §5.1): the vermilion ensō, "All done for today", and for a session the count, time and
 * how the answers split. No streaks, no confetti. AND-DECKEND: with [deck], "Deck done", when the deck's next learning
 * card is due, "Continue with all due cards" (when other decks have some) and "Back to decks". ADR-033: with [waiting],
 * a live "Next card in m:ss" countdown to the next learning card (the view model resumes the session then).
 */
@Composable
private fun Finished(
    stats: SessionStats?,
    modifier: Modifier,
    prefs: ReviewPrefs = ReviewPrefs(),
    deck: ReviewState.DeckDone? = null,
    intents: ReviewIntents? = null,
    meta: ReviewMeta = ReviewMeta(),
    waiting: ReviewState.Waiting? = null,
) {
    val c = KbTheme.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        KbEnso(size = 112.dp)
        if (waiting != null) {
            Text(
                T(R.string.review_waiting_title, countdown(waiting.nextDueAt)),
                style = KbTheme.type.headline,
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                T(R.string.review_waiting_body),
                style = KbTheme.type.body,
                color = c.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        } else Text(
            T(
                when {
                    deck != null -> R.string.review_deck_done_title
                    stats == null && meta.moreAvailable -> R.string.review_done_for_now_title
                    stats == null && meta.keepingGoing -> R.string.review_caught_up_title
                    stats == null -> R.string.review_finished_title
                    else -> R.string.review_summary_title
                },
            ),
            style = KbTheme.type.headline,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (waiting == null) Text(
            T(
                when {
                    deck != null -> R.string.review_deck_done_body
                    stats == null && meta.moreAvailable -> R.string.review_done_for_now_body
                    stats == null && meta.keepingGoing -> R.string.review_caught_up_body
                    stats == null -> R.string.review_finished_body
                    // AND-KEEPGOING-FIX: keep going ends only when nothing at all is left today
                    meta.keepingGoing && !meta.moreAvailable -> R.string.review_caught_up_body
                    else -> R.string.review_summary_body
                },
            ),
            style = KbTheme.type.body,
            color = c.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        deck?.nextLearningDueAt?.let { at ->
            val time = remember(at) { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)) }
            Text(
                T(R.string.review_deck_done_next, time),
                style = KbTheme.type.bodySm,
                color = c.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        if (stats != null) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Stat(stats.answered.toString(), T(R.string.review_stat_answered))
                Stat(formatDuration(stats.totalMs), T(R.string.review_stat_time))
            }
            val order = prefs.buttons + Button.entries.filter { it !in prefs.buttons }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (b in order) {
                    val n = stats.byButton[b] ?: continue
                    val tone = c.grade(gradeOf(b))
                    Text(
                        T(R.string.review_summary_buttons, T(buttonLabel(b)), n),
                        style = KbTheme.type.label,
                        color = tone.content,
                        modifier = Modifier.clip(RoundedCornerShape(KbRadius.sm)).background(tone.container).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (deck != null && deck.othersDue && intents != null) {
            KbButton(T(R.string.review_deck_done_continue), intents::continueWithAll, Modifier.padding(top = 8.dp))
        }
        if (deck == null && meta.moreAvailable && intents != null) {
            KbButton(T(R.string.review_keep_going), intents::keepGoing, Modifier.padding(top = 8.dp))
        }
        // Not a dead end: a quiet way back to Today (the same exit as the header's close button), or to the decks.
        val deckExit = LocalDeckExit.current.takeIf { deck != null }
        val exit = deckExit ?: LocalReviewExit.current
        val label = if (deckExit != null) R.string.review_deck_done_back else R.string.review_finished_back
        if (exit != null) KbButton(T(label), exit, Modifier.padding(top = 8.dp), variant = KbButtonVariant.Quiet)
    }
}

/** ADR-033: the time left until [at], ticking every second ("m:ss", "h:mm:ss" from an hour); never negative. */
@Composable
private fun countdown(at: Long): String {
    var now by remember(at) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        // bounded by tick count, not by the wall clock, so a virtual test clock cannot spin here
        repeat(((at - now).coerceAtLeast(0L) / 1_000L + 1L).toInt()) {
            if (now >= at) return@LaunchedEffect
            delay(1_000L - (at - now) % 1_000L)
            now = System.currentTimeMillis()
        }
    }
    val s = ((at - now).coerceAtLeast(0L) + 999L) / 1_000L
    val h = (s / 3_600).toInt()
    val m = (s % 3_600 / 60).toInt()
    val sec = (s % 60).toInt()
    return if (h > 0) T(R.string.review_waiting_clock_hms, h, m, sec) else T(R.string.review_waiting_clock_ms, m, sec)
}

@Composable
private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s < 60) T(R.string.review_time_seconds, s) else T(R.string.review_time_minutes, s / 60, s % 60)
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = KbTheme.type.display, color = KbTheme.colors.text)
        Text(label, style = KbTheme.type.bodySm, color = KbTheme.colors.textMuted)
    }
}

/** The edit sheet for the card's note. */
@Composable
private fun EditSheet(note: kotobaya.android.review.actions.EditableNote, cardId: String, intents: ReviewIntents) {
    val values = remember(note) { mutableStateMapOf<String, String>().apply { putAll(note.values) } }
    KbSheet(onDismiss = intents::dismissEdit, title = T(R.string.review_edit_title)) {
        for (k in note.keys) {
            KbTextField(values[k].orEmpty(), { values[k] = it }, label = k, singleLine = false, textStyle = KbTheme.type.body.copy(localeList = kotobaya.android.ui.theme.JapaneseLocale))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KbButton(T(R.string.review_edit_save), { intents.saveEdit(cardId, values.toMap()) }, Modifier.fillMaxWidth())
            KbButton(T(R.string.review_edit_cancel), intents::dismissEdit, Modifier.fillMaxWidth(), variant = KbButtonVariant.Quiet)
        }
    }
}

private const val SWIPE_PX = 120f
private const val TOAST_MS = 5_000L

@androidx.annotation.StringRes
private fun kindLabelRes(noteType: String): Int? = when (noteType) {
    "vocab" -> R.string.review_kind_vocab
    "kanji" -> R.string.review_kind_kanji
    "phrase" -> R.string.review_kind_phrase
    "grammar" -> R.string.review_kind_grammar
    "sentence" -> R.string.review_kind_sentence
    "custom" -> R.string.review_kind_custom
    else -> null
}
