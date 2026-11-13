package kotobaya.android.struggle.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotobaya.android.R
import kotobaya.android.grammar.ui.CaptureEntrySlot
import kotobaya.android.i18n.T
import kotobaya.android.media.browse.Loaded
import kotobaya.android.review.ui.GradeBar
import kotobaya.android.struggle.ConfusableView
import kotobaya.android.struggle.HotWordView
import kotobaya.android.struggle.StruggleConfusablesState
import kotobaya.android.struggle.StruggleDrillState
import kotobaya.android.struggle.StruggleHomeState
import kotobaya.android.struggle.StruggleIntents
import kotobaya.android.struggle.StruggleLeechesState
import kotobaya.android.struggle.StrugglePage
import kotobaya.android.struggle.StruggleUiState
import kotobaya.android.struggle.StruggleWordState
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbButtonSize
import kotobaya.android.ui.components.KbButtonVariant
import kotobaya.android.ui.components.KbChip
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbEmptyState
import kotobaya.android.ui.components.KbEmptyTone
import kotobaya.android.ui.components.KbEnso
import kotobaya.android.ui.components.KbIconTile
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbPageTitle
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbProgressBar
import kotobaya.android.ui.components.KbSectionTitle
import kotobaya.android.ui.components.KbSkeletonRows
import kotobaya.android.ui.components.KbStateChip
import kotobaya.android.ui.components.KbTagChip
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.components.KbTopBar
import kotobaya.android.ui.components.jaPhrase
import kotobaya.android.ui.components.jaTitle
import kotobaya.android.ui.theme.KbCardState
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbRadius
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.feedback
import kotobaya.android.ui.theme.ja
import kotobaya.android.ui.theme.state
import kotobaya.contracts.fsrs.Button

/** Content width of the Struggle pages. */
private val PAGE_MAX = 760.dp

/** The struggle screen (DESIGN §5.6): the page in [state]; [captureEntry] is each sentence's grammar capture. */
@Composable
fun StruggleScreen(state: StruggleUiState, intents: StruggleIntents, captureEntry: CaptureEntrySlot) {
    val page = state.page
    if (page != StrugglePage.Home) BackHandler(onBack = intents::home)
    Box(Modifier.fillMaxSize().background(KbTheme.colors.bg), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = PAGE_MAX).fillMaxSize()) {
            when (page) {
                StrugglePage.Home -> HomePage(state.home, intents)
                is StrugglePage.Word -> WordPage(page.word, state.word, intents, captureEntry)
                StrugglePage.Leeches -> LeechesPage(state.leeches, intents)
                StrugglePage.Confusables -> ConfusablesPage(state.confusables, intents)
                is StrugglePage.Drill -> DrillPage(state.drill, intents)
            }
        }
    }
}

/** Loading / needs-connection / error for one block; [content] with the data (and a cached-copy note). */
private fun <T> LazyListScope.loadedBlock(state: Loaded<T>?, content: LazyListScope.(T) -> Unit) {
    when (state) {
        null -> item { Pad { KbPanel(padding = PaddingValues(0.dp)) { KbSkeletonRows(3) } } }
        is Loaded.NeedsConnection -> item {
            Pad { KbPanel { KbEmptyState(T(R.string.struggle_needs_connection), icon = KbIcons.Offline) } }
        }
        is Loaded.Failed -> item {
            Pad { KbPanel { KbEmptyState(T(R.string.struggle_error), icon = KbIcons.Alert, tone = KbEmptyTone.Error) } }
        }
        is Loaded.Data -> {
            if (state.fromCache) item { Pad { KbBanner(T(R.string.struggle_cached), icon = KbIcons.Offline) } }
            content(state.value)
        }
    }
}

@Composable
private fun Pad(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { content() }
}

@Composable
private fun scoreLine(score: Double, failures: Int): String =
    T(R.string.struggle_failures, failures) + " · " + T(R.string.struggle_score, "%.1f".format(score))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePage(state: StruggleHomeState, intents: StruggleIntents) {
    val c = KbTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            KbPageTitle(
                T(R.string.struggle_title),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                subtitle = T(R.string.struggle_drill_note),
            )
        }
        item {
            Pad {
                KbPanel(padding = PaddingValues(0.dp)) {
                    KbListRow(
                        T(R.string.struggle_leeches),
                        supporting = T(R.string.struggle_leeches_body),
                        leading = { KbIconTile(KbIcons.Flag, container = c.state(KbCardState.Leech).container, tint = c.state(KbCardState.Leech).content) },
                        onClick = { intents.open(StrugglePage.Leeches) },
                    )
                    KbDivider(inset = 64.dp)
                    KbListRow(
                        T(R.string.struggle_confusables),
                        supporting = T(R.string.struggle_confusables_body),
                        leading = { KbIconTile(KbIcons.Link, container = c.primaryContainer, tint = c.onPrimaryContainer) },
                        onClick = { intents.open(StrugglePage.Confusables) },
                    )
                }
            }
        }
        item {
            Pad {
                KbButton(
                    T(R.string.struggle_drill_hot), { intents.open(StrugglePage.Drill("hot_words")) }, Modifier.fillMaxWidth(),
                    leading = KbIcons.Flame, size = KbButtonSize.Lg,
                )
            }
        }
        item { KbSectionTitle(T(R.string.struggle_hot_words), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) }
        loadedBlock(state.words) { words ->
            item {
                Pad {
                    KbPanel(padding = PaddingValues(0.dp)) {
                        if (words.isEmpty()) KbEmptyState(T(R.string.struggle_hot_words_none), tone = KbEmptyTone.Clear)
                        words.forEachIndexed { i, hw ->
                            if (i > 0) KbDivider(inset = 16.dp)
                            HotWordRow(hw) { intents.open(StrugglePage.Word(hw)) }
                        }
                    }
                }
            }
        }
        item { KbSectionTitle(T(R.string.struggle_hot_kanji), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) }
        loadedBlock(state.kanji) { kanji ->
            item {
                Pad {
                    if (kanji.isEmpty()) {
                        KbPanel { KbEmptyState(T(R.string.struggle_hot_kanji_none), tone = KbEmptyTone.Clear) }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            kanji.forEach { hk ->
                                Column(
                                    Modifier.widthIn(min = 104.dp, max = 160.dp).clip(RoundedCornerShape(KbRadius.lg)).background(c.surface)
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(hk.literal, style = KbTheme.type.jpHero.copy(fontSize = 40.sp, lineHeight = 48.sp), color = c.text)
                                    Text(hk.meanings.take(2).joinToString(", "), style = KbTheme.type.caption, color = c.text, textAlign = TextAlign.Center, maxLines = 2)
                                    Text(T(R.string.struggle_kanji_words, hk.wordCount), style = KbTheme.type.caption, color = c.textMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotWordRow(hw: HotWordView, onClick: () -> Unit) {
    KbListRow(
        title = hw.headword,
        titleStyle = jaTitle(),
        supporting = listOf(hw.reading, hw.glosses.take(3).joinToString("; ")).filter { it.isNotBlank() }.joinToString(" · "),
        meta = scoreLine(hw.score, hw.failures14d),
        onClick = onClick,
    )
}

@Composable
private fun WordPage(word: HotWordView, state: StruggleWordState, intents: StruggleIntents, captureEntry: CaptureEntrySlot) {
    val c = KbTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { KbTopBar("", onBack = intents::home, backLabel = T(R.string.struggle_back)) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(word.headword, style = KbTheme.type.jpDisplay.jaPhrase(), color = c.text)
                if (word.reading.isNotEmpty()) Text(word.reading, style = KbTheme.type.reading, color = c.textMuted)
                if (word.glosses.isNotEmpty()) Text(word.glosses.joinToString("; "), style = KbTheme.type.bodyLg, color = c.text)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KbChip(T(R.string.struggle_failures, word.failures14d), c.feedback(KbFeedback.Danger))
                }
                Text(T(R.string.struggle_extra_drill_note), style = KbTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        state.message?.let { m -> item { Pad { KbBanner(T(m), kind = if (m == R.string.struggle_write_failed) KbFeedback.Danger else KbFeedback.Success) } } }
        item { KbSectionTitle(T(R.string.struggle_mnemonic), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) }
        item {
            Pad {
                KbPanel {
                    KbTextField(
                        state.mnemonic, intents::setMnemonic, label = T(R.string.struggle_mnemonic_label),
                        singleLine = false, minLines = 2, textStyle = KbTheme.type.body.ja(),
                    )
                    KbButton(T(R.string.struggle_mnemonic_save), intents::saveMnemonic, Modifier.padding(top = 12.dp).align(Alignment.End), variant = KbButtonVariant.Tonal)
                }
            }
        }
        item { KbSectionTitle(T(R.string.struggle_sentences), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) }
        if (word.entrySeq == null) {
            item { Pad { KbPanel { KbEmptyState(T(R.string.struggle_sentences_none), icon = KbIcons.Book) } } }
        } else {
            loadedBlock(state.sentences) { sentences ->
                if (sentences.isEmpty()) item { Pad { KbPanel { KbEmptyState(T(R.string.struggle_sentences_none), icon = KbIcons.Book) } } }
                itemsIndexed(sentences, key = { _, s -> s.id }) { _, sent ->
                    Pad {
                        KbPanel {
                            Text(sent.text, style = KbTheme.type.jpBody.jaPhrase(), color = c.text)
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                captureEntry(sent.id, sent.mediaId, sent.text)
                                KbButton(T(R.string.struggle_make_card), { intents.makeSentenceCard(sent) }, variant = KbButtonVariant.Outline)
                            }
                        }
                    }
                }
            }
        }
        item { KbSectionTitle(T(R.string.struggle_partners), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) }
        loadedBlock(state.partners) { partners ->
            item {
                Pad {
                    KbPanel(padding = PaddingValues(0.dp)) {
                        if (partners.isEmpty()) KbEmptyState(T(R.string.struggle_partners_none), tone = KbEmptyTone.Clear)
                        partners.forEachIndexed { i, p ->
                            if (i > 0) KbDivider(inset = 16.dp)
                            KbListRow("${p.labelA} ／ ${p.labelB}", titleStyle = jaTitle(), supporting = "${reasonLabel(p.reason)} · ${statusLabel(p.status)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeechesPage(state: StruggleLeechesState, intents: StruggleIntents) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { KbTopBar(T(R.string.struggle_leeches), onBack = intents::home, backLabel = T(R.string.struggle_back)) }
        item {
            Pad {
                KbButton(T(R.string.struggle_drill_leeches), { intents.open(StrugglePage.Drill("leeches")) }, Modifier.fillMaxWidth(), leading = KbIcons.Flame)
            }
        }
        if (state.load !is Loaded.Data) {
            loadedBlock(state.load) {}
        } else {
            item {
                Pad {
                    KbPanel(padding = PaddingValues(0.dp)) {
                        if (state.rows.isEmpty()) KbEmptyState(T(R.string.struggle_leeches_none), body = T(R.string.struggle_none_body), tone = KbEmptyTone.Clear)
                        state.rows.forEachIndexed { i, l ->
                            if (i > 0) KbDivider(inset = 16.dp)
                            KbListRow(
                                l.label,
                                titleStyle = jaTitle(),
                                supporting = T(R.string.struggle_lapses, l.lapses),
                                trailing = if (l.suspended) {
                                    { KbStateChip(KbCardState.Suspended, T(R.string.struggle_suspended)) }
                                } else {
                                    { KbStateChip(KbCardState.Leech, T(R.string.struggle_leech)) }
                                },
                            )
                        }
                    }
                }
            }
            if (state.cursor != null) {
                item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { KbButton(T(R.string.struggle_more), intents::moreLeeches, variant = KbButtonVariant.Outline) } }
            }
        }
    }
}

@Composable
private fun ConfusablesPage(state: StruggleConfusablesState, intents: StruggleIntents) {
    val c = KbTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { KbTopBar(T(R.string.struggle_confusables), onBack = intents::home, backLabel = T(R.string.struggle_back)) }
        item {
            Pad {
                KbButton(T(R.string.struggle_drill_confusables), { intents.open(StrugglePage.Drill("confusables")) }, Modifier.fillMaxWidth(), leading = KbIcons.Flame)
            }
        }
        state.message?.let { m -> item { Pad { KbBanner(T(m), kind = if (m == R.string.struggle_write_failed) KbFeedback.Danger else KbFeedback.Success) } } }
        loadedBlock(state.pairs) { pairs ->
            if (pairs.isEmpty()) item { Pad { KbPanel { KbEmptyState(T(R.string.struggle_confusables_none), body = T(R.string.struggle_none_body), tone = KbEmptyTone.Clear) } } }
            itemsIndexed(pairs, key = { _, p -> "${p.noteAId}/${p.noteBId}/${p.reason}" }) { _, p ->
                Pad { PairCard(p, intents) }
            }
        }
    }
}

/** A confusable pair shown side by side in Japanese, its reason and status, and Confirm / Reject. */
@Composable
private fun PairCard(p: ConfusableView, intents: StruggleIntents) {
    val c = KbTheme.colors
    KbPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(p.labelA, style = KbTheme.type.title.jaPhrase(), color = c.text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("⇄", style = KbTheme.type.title, color = c.textMuted)
            Text(p.labelB, style = KbTheme.type.title.jaPhrase(), color = c.text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbTagChip(reasonLabel(p.reason))
            KbTagChip(statusLabel(p.status))
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KbButton(T(R.string.struggle_confirm), { intents.setConfusable(p, "confirmed") }, variant = KbButtonVariant.Tonal, enabled = p.status != "confirmed")
            KbButton(T(R.string.struggle_reject), { intents.setConfusable(p, "rejected") }, variant = KbButtonVariant.Outline, enabled = p.status != "rejected")
        }
    }
}

/** Flip-through drill (same layout as review, no intervals); every answer is logged with `mode = drill`. */
@Composable
private fun DrillPage(state: StruggleDrillState, intents: StruggleIntents) {
    val c = KbTheme.colors
    Column(Modifier.fillMaxSize()) {
        KbTopBar(T(R.string.struggle_drill_title), onBack = intents::home, backLabel = T(R.string.struggle_back))
        val s = state.items
        if (s !is Loaded.Data) {
            LazyColumn { loadedBlock(s) {} }
            return@Column
        }
        val item = s.value.getOrNull(state.index)
        if (item == null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                KbEnso(size = 96.dp)
                Text(T(R.string.struggle_drill_done), style = KbTheme.type.headline, color = c.text)
                Text(T(R.string.struggle_drill_note), style = KbTheme.type.body, color = c.textMuted, textAlign = TextAlign.Center)
                KbButton(T(R.string.struggle_back_to_struggle), intents::home, variant = KbButtonVariant.Tonal)
            }
            return@Column
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KbProgressBar((state.index).toFloat() / s.value.size, Modifier.weight(1f))
                Text(T(R.string.struggle_drill_progress, state.index + 1, s.value.size), style = KbTheme.type.caption, color = c.textMuted)
            }
            if (state.failed) KbBanner(T(R.string.struggle_write_failed), kind = KbFeedback.Danger)
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clip(KbTheme.shapes.lg).background(c.surface),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.padding(24.dp).padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(item.front, style = KbTheme.type.jpDisplay.jaPhrase(), color = c.text, textAlign = TextAlign.Center)
                    if (state.revealed) {
                        KbDivider()
                        Text(item.back, style = KbTheme.type.bodyLg.ja(), color = c.text, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!state.revealed) {
                KbButton(T(R.string.struggle_drill_show), intents::reveal, Modifier.fillMaxWidth().heightIn(min = 72.dp), size = KbButtonSize.Lg)
            } else {
                GradeBar(listOf(Button.AGAIN, Button.GOOD), previews = null, keyHints = false, enabled = true, onGrade = { b -> intents.answerDrill(if (b == Button.AGAIN) 1 else 3) })
            }
        }
    }
}

@Composable
private fun reasonLabel(reason: String): String = when (reason) {
    "homophone" -> T(R.string.struggle_reason_homophone)
    "look_alike" -> T(R.string.struggle_reason_look_alike)
    "meaning" -> T(R.string.struggle_reason_meaning)
    else -> T(R.string.struggle_reason_other)
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "suggested" -> T(R.string.struggle_status_suggested)
    "confirmed" -> T(R.string.struggle_status_confirmed)
    "rejected" -> T(R.string.struggle_status_rejected)
    else -> T(R.string.struggle_status_other)
}
