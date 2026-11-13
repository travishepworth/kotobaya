package kotobaya.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbTheme

/** Panel tone (DESIGN §3 Card surface). */
enum class KbTone { Surface, Sunken, Raised }

/**
 * A panel on the canvas: radius lg, hairline border, elevation 0 by default (only the review card, menus and
 * dialogs lift). [padding] is applied inside.
 */
@Composable
fun KbPanel(
    modifier: Modifier = Modifier,
    tone: KbTone = KbTone.Surface,
    padding: PaddingValues = PaddingValues(16.dp),
    elevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = KbTheme.colors
    val shape = KbTheme.shapes.lg
    val bg = when (tone) {
        KbTone.Surface -> c.surface
        KbTone.Sunken -> c.surfaceSunken
        KbTone.Raised -> c.surfaceRaised
    }
    Column(
        modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = c.scrim, spotColor = c.scrim) else Modifier)
            .clip(shape)
            .background(bg)
            .then(if (tone == KbTone.Sunken) Modifier else Modifier.border(1.dp, c.border, shape))
            .padding(padding),
        content = content,
    )
}

/** Overline eyebrow above a group (Latin only, never Japanese). */
@Composable
fun KbOverline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = KbTheme.type.overline,
        color = KbTheme.colors.textMuted,
        modifier = modifier.semantics { heading() },
    )
}

/** A section title with an optional trailing action (for example "See all"). */
@Composable
fun KbSectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = KbTheme.type.title,
            color = KbTheme.colors.text,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** A 1 dp hairline. [inset] indents it from the start (list rows with a leading element). */
@Composable
fun KbDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(start = inset).height(1.dp).background(KbTheme.colors.border))
}

/** Centres [content] and caps its width (content max, reading measure, review card). */
@Composable
fun KbMaxWidth(maxWidth: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

/** A page title in the headline role with an optional muted line under it. */
@Composable
fun KbPageTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier) {
        Text(title, style = KbTheme.type.headline, color = KbTheme.colors.text, modifier = Modifier.semantics { heading() })
        if (subtitle != null) {
            Text(subtitle, style = KbTheme.type.bodySm, color = KbTheme.colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
