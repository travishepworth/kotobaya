package kotobaya.android.di

import org.koin.core.module.Module

/** What a feature package contributes: its Koin module. */
data class FeatureEntry(
    val id: String,
    val module: Module? = null,
)

/** Aggregated view over feature entries. */
class FeatureRegistry(val entries: List<FeatureEntry>) {
    val modules: List<Module> get() = entries.mapNotNull { it.module }
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
    kotobaya.android.media.browse.mediaBrowseFeature,
    kotobaya.android.notes.notesFeature,
    kotobaya.android.ankiapi.takoboto.takobotoFeature,
    kotobaya.android.intake.intakeFeature,
    kotobaya.android.ankiconnect.ankiConnectFeature,
    kotobaya.android.sync.syncFeature,
    kotobaya.android.report.reportFeature,
    kotobaya.android.shell.shellFeature, // SHELL-A2: local API kernel (ADR-036)
)
