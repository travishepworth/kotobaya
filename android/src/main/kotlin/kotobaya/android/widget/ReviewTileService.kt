package kotobaya.android.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotobaya.android.R

/** Quick Settings tile: tap opens the review screen; the subtitle shows the due count (local DB, no network). */
class ReviewTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Replaced in tests; the default really launches and collapses the shade. */
    internal var launcher: (Intent) -> Unit = ::startReview

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { WidgetSnapshots.load() }
            val tile = qsTile ?: return@launch
            tile.state = if (snapshot.due > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(R.string.tile_subtitle, snapshot.due)
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        launcher(WidgetIntents.review(this))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startReview(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, ReviewTileService::class.java))
        }
    }
}
