package kotobaya.android.styleguide

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotobaya.android.i18n.T
import kotobaya.android.ui.theme.KbColors
import kotobaya.android.ui.theme.KbTheme
import kotlin.reflect.KProperty1

/** A titled block of a page (DESIGN.md section name). */
@Composable
fun SgGroup(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KbTheme.space.s2)) {
        Text(T(title).uppercase(), style = KbTheme.type.overline, color = KbTheme.colors.textMuted)
        content()
    }
}

/** Items that wrap onto as many lines as the width needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SgFlow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2),
        verticalArrangement = Arrangement.spacedBy(KbTheme.space.s2),
    ) { content() }
}

/** A token name and its value in caption type (identifiers, not prose, so not translated). */
@Composable
fun SgTokenLabel(name: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(name, style = KbTheme.type.label, color = KbTheme.colors.text)
        Text(value, style = KbTheme.type.caption, color = KbTheme.colors.textMuted)
    }
}

/** One colour token: a swatch with a hairline (so light-on-light reads) plus name and hex. */
@Composable
fun SgSwatch(token: KProperty1<KbColors, Color>) {
    val c = token.get(KbTheme.colors)
    Row(
        Modifier.widthIn(min = 168.dp).padding(end = KbTheme.space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KbTheme.space.s2),
    ) {
        Box(
            Modifier.size(KbTheme.size.iconLg + KbTheme.space.s2)
                .background(c, KbTheme.shapes.sm)
                .border(1.dp, KbTheme.colors.border, KbTheme.shapes.sm),
        )
        SgTokenLabel(token.name, c.hex())
    }
}
