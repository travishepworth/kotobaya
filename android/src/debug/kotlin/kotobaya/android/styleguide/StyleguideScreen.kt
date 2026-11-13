package kotobaya.android.styleguide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotobaya.android.R
import kotobaya.android.i18n.T
import kotobaya.android.ui.theme.KbTheme
import kotobaya.android.ui.theme.KotobayaTheme
import kotobaya.android.ui.theme.ThemeMode

/** The styleguide's pages; [id] is the stable screenshot folder name. Each fits roughly one phone screen. */
enum class StyleguidePage(val id: String, @StringRes val title: Int, val content: @Composable () -> Unit) {
    ColorsBase("colors-base", R.string.sg_page_colors_base, { ColorsBasePage() }),
    ColorsRoles("colors-roles", R.string.sg_page_colors_roles, { ColorsRolesPage() }),
    ColorsDecks("colors-decks", R.string.sg_page_colors_decks, { ColorsDecksPage() }),
    Type("type", R.string.sg_page_type, { TypePage() }),
    SpaceShape("space-shape", R.string.sg_page_space_shape, { SpaceShapePage() }),
    SizeMotion("size-motion", R.string.sg_page_size_motion, { SizeMotionPage() }),
    Actions("components-actions", R.string.sg_page_actions, { ActionsPage() }),
    Lists("components-lists", R.string.sg_page_lists, { ListsPage() }),
    Feedback("components-feedback", R.string.sg_page_feedback, { FeedbackPage() }),
    Review("components-review", R.string.sg_page_review, { ReviewPage() }),
}

/** One page's body: title then its groups, centred at the reading measure on wide windows. */
@Composable
fun StyleguidePageBody(page: StyleguidePage, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = KbTheme.size.measure).fillMaxWidth().padding(KbTheme.space.s4),
        verticalArrangement = Arrangement.spacedBy(KbTheme.space.s4),
    ) {
        Text(T(page.title), style = KbTheme.type.headline, color = KbTheme.colors.text)
        page.content()
    }
}

/** A single page on the canvas, in the given theme mode: what the screenshot test captures. */
@Composable
fun StyleguidePageFrame(page: StyleguidePage, mode: ThemeMode = ThemeMode.System) {
    KotobayaTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = KbTheme.colors.bg) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                StyleguidePageBody(page)
            }
        }
    }
}

/** The whole styleguide with the System / Light / Dark override (DESIGN §2.5) at the top. */
@Composable
fun StyleguideScreen() {
    var mode by rememberSaveable { mutableStateOf(ThemeMode.System) }
    KotobayaTheme(mode) {
        Surface(Modifier.fillMaxSize(), color = KbTheme.colors.bg) {
            LazyColumn(Modifier.safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                item { ModeSwitch(mode) { mode = it } }
                items(StyleguidePage.entries, key = { it.id }) { page ->
                    StyleguidePageBody(page)
                    HorizontalDivider(Modifier.widthIn(max = KbTheme.size.measure), color = KbTheme.colors.border)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SgHeader {
        Text(T(R.string.sg_title), style = KbTheme.type.headline, color = KbTheme.colors.text)
        Text(T(R.string.sg_theme), style = KbTheme.type.label, color = KbTheme.colors.textMuted)
        val labels = mapOf(
            ThemeMode.System to R.string.sg_theme_system,
            ThemeMode.Light to R.string.sg_theme_light,
            ThemeMode.Dark to R.string.sg_theme_dark,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = { onChange(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                ) { Text(T(labels.getValue(m))) }
            }
        }
        Text(
            T(if (KbTheme.isDark) R.string.sg_now_dark else R.string.sg_now_light),
            style = KbTheme.type.caption,
            color = KbTheme.colors.textMuted,
        )
    }
}

@Composable
private fun SgHeader(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = KbTheme.size.measure).fillMaxWidth().padding(KbTheme.space.s4),
        verticalArrangement = Arrangement.spacedBy(KbTheme.space.s2),
        content = content,
    )
}

/** Debug-only host for [StyleguideScreen], opened from Settings › Styleguide (debug). */
class StyleguideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StyleguideScreen() }
    }
}
