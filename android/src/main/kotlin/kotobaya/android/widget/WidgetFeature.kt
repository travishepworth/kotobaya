package kotobaya.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotobaya.android.di.FeatureEntry
import kotobaya.android.notify.DueListener
import kotobaya.android.queue.LocalQueueLoader
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.binds
import org.koin.dsl.module

/** T308's [DueListener]: refresh the widget and the tile. Reads only the local DB; never touches the network. */
class WidgetUpdater(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val refresh: suspend (Context) -> Unit = { KotobayaWidget().updateAll(it) },
    private val refreshTile: (Context) -> Unit = ReviewTileService::requestRefresh,
) : DueListener {
    override fun onDueChanged() {
        scope.launch {
            runCatching { refresh(context) }
            runCatching { refreshTile(context) }
        }
    }
}

val widgetModule = module {
    factory { WidgetData(get<LocalQueueLoader>(), SqliteWidgetSource(androidContext())) }
    single { WidgetUpdater(androidContext()) } binds arrayOf(DueListener::class)
}

val widgetFeature = FeatureEntry(id = "widget", module = widgetModule)
