package kotobaya.android.settings.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotobaya.android.R
import kotobaya.android.di.SettingsSection
import kotobaya.android.i18n.T
import kotobaya.android.nav.Seal
import kotobaya.android.settings.AboutUiState
import kotobaya.android.settings.StudyKey
import kotobaya.android.settings.StudySettingsIntents
import kotobaya.android.settings.StudySettingsUiState
import kotobaya.android.ui.components.KbBanner
import kotobaya.android.ui.components.KbButton
import kotobaya.android.ui.components.KbDivider
import kotobaya.android.ui.components.KbFactRow
import kotobaya.android.ui.components.KbIcons
import kotobaya.android.ui.components.KbListRow
import kotobaya.android.ui.components.KbNavRow
import kotobaya.android.ui.components.KbOverline
import kotobaya.android.ui.components.KbPanel
import kotobaya.android.ui.components.KbSegmented
import kotobaya.android.ui.components.KbTextField
import kotobaya.android.ui.components.KbTopBar
import kotobaya.android.ui.theme.KbFeedback
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.ThemeMode

/** A group of the Settings index (DESIGN §5.11): its title, icon and the section ids it holds, in order. */
private data class Group(@StringRes val title: Int, val icon: ImageVector, val ids: List<String>)

private val GROUPS = listOf(
    Group(R.string.settings_group_appearance, KbIcons.Contrast, listOf(APPEARANCE)),
    Group(R.string.settings_group_study, KbIcons.Target, listOf("study")),
    Group(R.string.settings_group_sync, KbIcons.Sync, listOf("devices", "sync", "push")),
    Group(R.string.settings_group_notifications, KbIcons.Bell, listOf("notify")),
    Group(R.string.settings_group_offline, KbIcons.Download, listOf("dict", "media")),
    Group(R.string.settings_group_integrations, KbIcons.Link, listOf("ankiconnect", "takoboto")),
    Group(R.string.settings_group_developer, KbIcons.Wrench, listOf("demo", "styleguide")),
    Group(R.string.settings_group_about, KbIcons.Info, listOf("about")),
)

private const val APPEARANCE = "appearance"

/** Section order: devices (pairing) first, then sync, the rest in registry order, about last. */
fun orderedSections(sections: List<SettingsSection>): List<SettingsSection> {
    val known = GROUPS.flatMap { it.ids }
    return sections.sortedBy { s -> known.indexOf(s.id).let { if (it < 0) known.indexOf("about") - 1 else it } }
}

/**
 * The Settings tab (DESIGN §5.11): grouped rows on an index; a row opens its section. Phones show one at a time
 * (the section is a pushed page); from 840 dp the index and the section sit side by side. [sections] come from the
 * features; Appearance is the shell's own ([appearance], [onAppearance]). [initialSection] opens one directly.
 */
@Composable
fun SettingsScreen(
    sections: List<SettingsSection>,
    appearance: ThemeMode = ThemeMode.System,
    onAppearance: (ThemeMode) -> Unit = {},
    initialSection: String? = null,
) {
    val all = listOf(SettingsSection(APPEARANCE, R.string.appearance_title) { AppearanceSection(appearance, onAppearance) }) + sections
    var open by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    BoxWithConstraints(Modifier.fillMaxSize().background(KbTheme.colors.bg)) {
        if (maxWidth >= KbTheme.breakpoint.expanded) {
            val shown = all.firstOrNull { it.id == open } ?: all.first()
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(360.dp).fillMaxHeight()) { SettingsIndex(all, shown.id) { open = it } }
                Box(Modifier.width(1.dp).fillMaxHeight().background(KbTheme.colors.border))
                Box(Modifier.weight(1f).fillMaxHeight()) { SectionPage(shown, onBack = null) }
            }
        } else {
            val shown = all.firstOrNull { it.id == open }
            if (shown == null) {
                SettingsIndex(all, null) { open = it }
            } else {
                BackHandler { open = null }
                SectionPage(shown, onBack = { open = null })
            }
        }
    }
}

@Composable
private fun SettingsIndex(sections: List<SettingsSection>, selected: String?, onOpen: (String) -> Unit) {
    val byId = sections.associateBy { it.id }
    val known = GROUPS.flatMap { it.ids }.toSet()
    val others = sections.filter { it.id !in known }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(T(R.string.nav_settings), style = KbTheme.type.headline, color = KbTheme.colors.text)
        val groups = GROUPS.map { g -> g to g.ids.mapNotNull { byId[it] } } +
            listOf(Group(R.string.settings_group_other, KbIcons.Info, emptyList()) to others)
        for ((g, rows) in groups) {
            if (rows.isEmpty()) continue
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KbOverline(T(g.title), Modifier.padding(start = 4.dp))
                KbPanel(padding = PaddingValues(0.dp)) {
                    rows.forEachIndexed { i, s ->
                        if (i > 0) KbDivider(inset = 64.dp)
                        KbNavRow(T(s.titleRes), g.icon, onClick = { onOpen(s.id) }, selected = s.id == selected)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionPage(section: SettingsSection, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            KbTopBar(T(section.titleRes), onBack = onBack, backLabel = T(R.string.settings_back))
        } else {
            Text(
                T(section.titleRes),
                style = KbTheme.type.headline,
                color = KbTheme.colors.text,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = if (onBack != null) 16.dp else 24.dp, vertical = 12.dp),
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) { section.content() }
        }
    }
}

/** The spacing every section's content uses between its panels. */
@Composable
fun SettingsBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

/** Settings › Appearance: System / Light / Dark. */
@Composable
fun AppearanceSection(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SettingsBody {
        KbPanel {
            KbSegmented(
                listOf(
                    ThemeMode.System to T(R.string.appearance_system),
                    ThemeMode.Light to T(R.string.appearance_light),
                    ThemeMode.Dark to T(R.string.appearance_dark),
                ),
                selected = mode,
                onSelect = onChange,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(T(R.string.appearance_hint), style = KbTheme.type.bodySm, color = KbTheme.colors.textMuted, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Settings › Study. */
@Composable
fun StudySection(state: StudySettingsUiState, intents: StudySettingsIntents) {
    SettingsBody {
        KbPanel {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(
                    StudyKey.NEW_PER_DAY to R.string.study_new_per_day,
                    StudyKey.REVIEWS_PER_DAY to R.string.study_reviews_per_day,
                    StudyKey.ROLLOVER_HOUR to R.string.study_rollover_hour,
                ).forEach { (k, label) ->
                    KbTextField(
                        state.fields[k].orEmpty(),
                        { intents.setField(k, it) },
                        label = T(label),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                KbButton(T(R.string.study_save), intents::save, Modifier.align(Alignment.End))
            }
        }
        state.message?.let { m ->
            val kind = when (m) {
                R.string.study_saved -> KbFeedback.Success
                R.string.study_unchanged -> KbFeedback.Info
                else -> KbFeedback.Danger
            }
            KbBanner(T(m), kind = kind)
        }
    }
}

/** Settings › About. */
@Composable
fun AboutSection(state: AboutUiState) {
    SettingsBody {
        KbPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Seal()
                Column {
                    Text(T(R.string.app_name), style = KbTheme.type.title, color = KbTheme.colors.text)
                    Text(T(R.string.about_tagline), style = KbTheme.type.bodySm, color = KbTheme.colors.textMuted)
                }
            }
        }
        KbPanel(padding = PaddingValues(vertical = 4.dp)) {
            KbFactRow(T(R.string.about_version_label), state.appVersion)
            KbDivider(inset = 16.dp)
            KbFactRow(T(R.string.about_flavor_label), T(state.flavorRes))
            KbDivider(inset = 16.dp)
            KbFactRow(T(R.string.about_server_label), state.serverVersion ?: T(R.string.about_server_unavailable))
        }
    }
}

/** A static row used by sections that list facts with a title (kept for sections built on list rows). */
@Composable
fun SettingsInfoRow(title: String, supporting: String? = null) = KbListRow(title, supporting = supporting, titleStyle = KbTheme.type.body)
