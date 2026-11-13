package kotobaya.android.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotobaya.android.ui.theme.KbTheme

/** A key cap (DESIGN §3 Kbd) for keyboard hints; hidden from accessibility (the action itself is labelled). */
@Composable
fun KbKey(key: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = KbTheme.colors
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .defaultMinSize(minWidth = if (small) 18.dp else 24.dp)
            .clip(shape)
            .background(c.surface.copy(alpha = 0.7f))
            .border(1.dp, c.borderStrong, shape)
            .padding(horizontal = if (small) 4.dp else 6.dp, vertical = 0.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(key, style = KbTheme.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** True when a hardware keyboard is attached (tablets, Chromebooks, foldables with a keyboard): key hints show. */
@Composable
fun hasHardwareKeyboard(): Boolean = LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY
