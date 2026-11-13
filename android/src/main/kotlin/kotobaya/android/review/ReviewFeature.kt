package kotobaya.android.review

import androidx.compose.runtime.Composable
import kotobaya.android.R
import kotobaya.android.di.FeatureEntry
import kotobaya.android.nav.NavRoute
import kotobaya.android.nav.Routes
import kotobaya.android.queue.LocalQueueLoader
import org.koin.compose.getKoin

/** Adapts T304a's loader to the session's queue seam. */
fun LocalQueueLoader.asQueueSource(): QueueSource = QueueSource { newShown -> load(newShown).queue }

/**
 * Replaces the shell's Review placeholder. The screen needs a [ReviewBackend], which T303c's module registers
 * once the SQLDelight store exists; without one it shows a "not available" message.
 */
val reviewFeature = FeatureEntry(
    id = "review",
    routes = listOf(
        NavRoute(Routes.REVIEW, R.string.nav_review, topLevel = true) { ReviewRoute() },
    ),
)

@Composable
private fun ReviewRoute() {
    val backend = getKoin().getOrNull<ReviewBackend<*>>()
    ReviewScreen(backend)
}
