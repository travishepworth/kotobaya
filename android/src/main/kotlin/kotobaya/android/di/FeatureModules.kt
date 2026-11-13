package kotobaya.android.di

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import kotobaya.android.nav.NavRoute
import org.koin.core.module.Module

/** A section shown on the Settings screen, contributed by a feature. */
data class SettingsSection(
    val id: String,
    @StringRes val titleRes: Int,
    val content: @Composable () -> Unit,
)

/** Everything a feature package contributes to the shell. Later tickets add one per package. */
data class FeatureEntry(
    val id: String,
    val module: Module? = null,
    val routes: List<NavRoute> = emptyList(),
    val settingsSections: List<SettingsSection> = emptyList(),
    /** Home screen sync status chip slot (T303e). */
    val homeStatusChip: (@Composable () -> Unit)? = null,
)

/** Aggregated view over feature entries; the nav graph and Settings read only this. */
class FeatureRegistry(val entries: List<FeatureEntry>) {
    val routes: List<NavRoute> get() = entries.flatMap { it.routes }
    val settingsSections: List<SettingsSection> get() = entries.flatMap { it.settingsSections }
    val modules: List<Module> get() = entries.mapNotNull { it.module }
    val homeStatusChips: List<@Composable () -> Unit> get() = entries.mapNotNull { it.homeStatusChip }
}

/**
 * THE feature registry. Each later ticket adds its package's FeatureEntry with ONE line here.
 * Nobody else edits the nav graph.
 */
val featureEntries: List<FeatureEntry> = listOf(
    kotobaya.android.pairing.pairingFeature,
    kotobaya.android.queue.queueFeature,
    kotobaya.android.dict.dictFeature,
    kotobaya.android.notify.notifyFeature,
    kotobaya.android.widget.widgetFeature,
    kotobaya.android.push.pushFeature,
    kotobaya.android.media.mediaFeature,
    kotobaya.android.sync.syncFeature,
    kotobaya.android.sync.ui.syncUiFeature,
)
