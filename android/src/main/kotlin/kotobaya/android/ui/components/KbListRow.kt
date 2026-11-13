package kotobaya.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import kotobaya.android.ui.theme.KbTheme

/**
 * The unit of every list (DESIGN §3 List row): [title] (title-sm; pass a Japanese style through [titleStyle]),
 * [supporting] (2-line clamp), [meta] (caption), [leading] and [trailing] slots. Min 56 dp. At large text the trailing
 * content moves under the text so nothing clips. [onClick] makes it a link row with a chevron; [selected] draws the
 * indigo bar and tint (list–detail).
 */
@Composable
fun KbListRow(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = KbTheme.type.titleSm,
    supporting: String? = null,
    supportingStyle: TextStyle = KbTheme.type.bodySm,
    meta: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    chevron: Boolean = onClick != null,
    titleMaxLines: Int = 2,
) {
    val c = KbTheme.colors
    val stacked = fontScale() >= LARGE_TEXT
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 56.dp)
            .background(if (selected) c.surfaceSelected else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (selected) c.primary else Color.Transparent))
        Row(
            Modifier.weight(1f).padding(start = 13.dp, end = if (chevron) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = titleStyle, color = c.text, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
                if (!supporting.isNullOrEmpty()) {
                    Text(supporting, style = supportingStyle, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!meta.isNullOrEmpty()) Text(meta, style = KbTheme.type.caption, color = c.textMuted)
                if (stacked && trailing != null) Box(Modifier.padding(top = 4.dp)) { trailing() }
            }
            if (!stacked && trailing != null) trailing()
        }
        if (chevron) {
            Icon(KbIcons.Chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.padding(end = 12.dp).size(20.dp))
        }
    }
}

/** A grouped list: rows inside a panel with hairlines between them ([inset] lines up with the text). */
@Composable
fun KbListGroup(modifier: Modifier = Modifier, content: @Composable KbListGroupScope.() -> Unit) {
    KbPanel(modifier, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        val scope = KbListGroupScope()
        scope.content()
    }
}

/** Marker scope of [KbListGroup]; rows separate themselves with [divider]. */
class KbListGroupScope {
    @Composable
    fun divider(inset: androidx.compose.ui.unit.Dp = 16.dp) = KbDivider(inset = inset)
}
