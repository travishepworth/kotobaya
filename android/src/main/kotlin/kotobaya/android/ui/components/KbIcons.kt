package kotobaya.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The few icons Material's core set lacks, drawn as 24 dp stroke icons (2 dp, round caps) so they sit with the
 * Rounded core icons. Tinted by `Icon` like any other vector.
 */
object KbIcons {
    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Two stacked cards: the Review destination. */
    val Cards: ImageVector = stroke("kb.cards") {
        // back card
        moveTo(8f, 4f); lineTo(18f, 4f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); lineTo(20f, 15f)
        // front card
        moveTo(6f, 8f); lineTo(14f, 8f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); lineTo(16f, 18f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); lineTo(6f, 20f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        lineTo(4f, 10f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
    }

    /** Three stacked layers: the Decks destination. */
    val Decks: ImageVector = stroke("kb.decks") {
        moveTo(12f, 3f); lineTo(21f, 7.5f); lineTo(12f, 12f); lineTo(3f, 7.5f); close()
        moveTo(3f, 12f); lineTo(12f, 16.5f); lineTo(21f, 12f)
        moveTo(3f, 16.5f); lineTo(12f, 21f); lineTo(21f, 16.5f)
    }

    /** Sun on the horizon: Today. */
    val Today: ImageVector = stroke("kb.today") {
        moveTo(4f, 18f); lineTo(20f, 18f)
        moveTo(7f, 18f); arcToRelative(5f, 5f, 0f, false, true, 10f, 0f)
        moveTo(12f, 6f); lineTo(12f, 8.5f)
        moveTo(5.5f, 9.5f); lineTo(7.2f, 11.2f)
        moveTo(18.5f, 9.5f); lineTo(16.8f, 11.2f)
    }

    /** Magnifier (drawn to match the stroke set). */
    val Search: ImageVector = stroke("kb.search") {
        moveTo(10.5f, 4f); arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, 13f); arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, -13f); close()
        moveTo(15.5f, 15.5f); lineTo(20f, 20f)
    }

    /** Sliders: Settings. */
    val Settings: ImageVector = stroke("kb.settings") {
        moveTo(4f, 7f); lineTo(11f, 7f); moveTo(17f, 7f); lineTo(20f, 7f)
        moveTo(14f, 4.5f); lineTo(14f, 9.5f)
        moveTo(4f, 17f); lineTo(7f, 17f); moveTo(13f, 17f); lineTo(20f, 17f)
        moveTo(10f, 14.5f); lineTo(10f, 19.5f)
    }

    /** Curved arrow back: Undo. */
    val Undo: ImageVector = stroke("kb.undo") {
        moveTo(9f, 5f); lineTo(4f, 10f); lineTo(9f, 15f)
        moveTo(4f, 10f); lineTo(14f, 10f); arcToRelative(5.5f, 5.5f, 0f, false, true, 0f, 11f); lineTo(11f, 21f)
    }

    /** Speaker with waves: play audio. */
    val Audio: ImageVector = stroke("kb.audio") {
        moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close()
        moveTo(16.5f, 9f); arcToRelative(4f, 4f, 0f, false, true, 0f, 6f)
        moveTo(19f, 6.5f); arcToRelative(7.5f, 7.5f, 0f, false, true, 0f, 11f)
    }

    /** Three dots: overflow menus. */
    val More: ImageVector = stroke("kb.more") {
        moveTo(12f, 5f); lineTo(12f, 5.01f); moveTo(12f, 12f); lineTo(12f, 12.01f); moveTo(12f, 19f); lineTo(12f, 19.01f)
    }

    val Back: ImageVector = stroke("kb.back") {
        moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }

    val Close: ImageVector = stroke("kb.close") {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Chevron: ImageVector = stroke("kb.chevron") {
        moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
    }

    val Check: ImageVector = stroke("kb.check") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
    }

    val Refresh: ImageVector = stroke("kb.refresh") {
        moveTo(19f, 12f); arcToRelative(7f, 7f, 0f, true, true, -2.05f, -4.95f)
        moveTo(19f, 4f); lineTo(19f, 8f); lineTo(15f, 8f)
    }

    /** Flame: words giving trouble (Struggle). */
    val Flame: ImageVector = stroke("kb.flame") {
        moveTo(12f, 3f); curveTo(12f, 7f, 18f, 9f, 18f, 14.5f); arcToRelative(6f, 6f, 0f, false, true, -12f, 0f)
        curveTo(6f, 11f, 8.5f, 9.5f, 9f, 7.5f); curveTo(10.5f, 9f, 10.5f, 10.5f, 10.5f, 11.5f); curveTo(12f, 10f, 12.5f, 6f, 12f, 3f); close()
    }

    /** Two arrows in a circle: sync. */
    val Sync: ImageVector = stroke("kb.sync") {
        moveTo(4.5f, 10f); arcToRelative(7.8f, 7.8f, 0f, false, true, 14.5f, -2.5f); moveTo(19f, 3.5f); lineTo(19f, 7.5f); lineTo(15f, 7.5f)
        moveTo(19.5f, 14f); arcToRelative(7.8f, 7.8f, 0f, false, true, -14.5f, 2.5f); moveTo(5f, 20.5f); lineTo(5f, 16.5f); lineTo(9f, 16.5f)
    }

    /** Circle with "i": information. */
    val Info: ImageVector = stroke("kb.info") {
        moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, true, 0f, 18f); arcToRelative(9f, 9f, 0f, true, true, 0f, -18f); close()
        moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.5f); lineTo(12f, 7.51f)
    }

    /** Triangle with "!": warnings and errors. */
    val Alert: ImageVector = stroke("kb.alert") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 10f); lineTo(12f, 14f); moveTo(12f, 16.8f); lineTo(12f, 16.81f)
    }

    /** Cloud with a slash: offline / needs a connection. */
    val Offline: ImageVector = stroke("kb.offline") {
        moveTo(7f, 18f); arcToRelative(4f, 4f, 0f, false, true, -0.5f, -7.97f); arcToRelative(6f, 6f, 0f, false, true, 11.3f, -1.4f)
        arcToRelative(4.5f, 4.5f, 0f, false, true, 0.7f, 8.9f)
        moveTo(4f, 4f); lineTo(20f, 20f)
    }

    /** Open book: dictionary. */
    val Book: ImageVector = stroke("kb.book") {
        moveTo(12f, 6.5f); curveTo(10f, 5f, 7f, 4.5f, 3.5f, 5f); lineTo(3.5f, 18.5f); curveTo(7f, 18f, 10f, 18.5f, 12f, 20f)
        curveTo(14f, 18.5f, 17f, 18f, 20.5f, 18.5f); lineTo(20.5f, 5f); curveTo(17f, 4.5f, 14f, 5f, 12f, 6.5f); close()
        moveTo(12f, 6.5f); lineTo(12f, 20f)
    }

    /** Phone outline: devices. */
    val Device: ImageVector = stroke("kb.device") {
        moveTo(8f, 3f); lineTo(16f, 3f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); lineTo(18f, 19f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); lineTo(8f, 21f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        lineTo(6f, 5f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
        moveTo(11f, 18f); lineTo(13f, 18f)
    }

    /** Bell: notifications. */
    val Bell: ImageVector = stroke("kb.bell") {
        moveTo(6f, 16.5f); lineTo(6f, 11f); arcToRelative(6f, 6f, 0f, false, true, 12f, 0f); lineTo(18f, 16.5f); lineTo(19.5f, 18f)
        lineTo(4.5f, 18f); close()
        moveTo(10f, 20.5f); lineTo(14f, 20.5f)
    }

    /** Half-filled circle: appearance. */
    val Contrast: ImageVector = stroke("kb.contrast") {
        moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, true, 0f, 18f); arcToRelative(9f, 9f, 0f, true, true, 0f, -18f); close()
        moveTo(12f, 3f); lineTo(12f, 21f)
        moveTo(12f, 7f); lineTo(16f, 7f); moveTo(12f, 11f); lineTo(19.5f, 11f); moveTo(12f, 15f); lineTo(18f, 15f)
    }

    /** Two linked rings: integrations. */
    val Link: ImageVector = stroke("kb.link") {
        moveTo(10f, 14f); lineTo(14f, 10f)
        moveTo(8.5f, 11f); lineTo(6.5f, 13f); arcToRelative(3.5f, 3.5f, 0f, false, false, 5f, 5f); lineTo(13.5f, 16f)
        moveTo(10.5f, 8f); lineTo(12.5f, 6f); arcToRelative(3.5f, 3.5f, 0f, false, true, 5f, 5f); lineTo(15.5f, 13f)
    }

    /** Download tray: offline storage and audio. */
    val Download: ImageVector = stroke("kb.download") {
        moveTo(12f, 4f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(4f, 17f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 17f)
    }

    /** Graduation-ish target: study settings. */
    val Target: ImageVector = stroke("kb.target") {
        moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, true, 0f, 18f); arcToRelative(9f, 9f, 0f, true, true, 0f, -18f); close()
        moveTo(12f, 8f); arcToRelative(4f, 4f, 0f, true, true, 0f, 8f); arcToRelative(4f, 4f, 0f, true, true, 0f, -8f); close()
    }

    /** Wrench: developer / debug sections. */
    val Wrench: ImageVector = stroke("kb.wrench") {
        moveTo(14.5f, 4f); arcToRelative(5f, 5f, 0f, false, false, 5.5f, 5.5f); lineTo(17.5f, 12f); arcToRelative(5f, 5f, 0f, false, true, -5.5f, -5.5f); close()
        moveTo(12.5f, 11.5f); lineTo(4.5f, 19.5f)
    }

    /** Plus. */
    val Add: ImageVector = stroke("kb.add") {
        moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** Flag. */
    val Flag: ImageVector = stroke("kb.flag") {
        moveTo(5f, 21f); lineTo(5f, 4f); lineTo(17f, 4f); lineTo(14.5f, 8f); lineTo(17f, 12f); lineTo(5f, 12f)
    }

    /** Pencil. */
    val Edit: ImageVector = stroke("kb.edit") {
        moveTo(4f, 20f); lineTo(4.5f, 16f); lineTo(15.5f, 5f); lineTo(19f, 8.5f); lineTo(8f, 19.5f); close()
        moveTo(13.5f, 7f); lineTo(17f, 10.5f)
    }
}
