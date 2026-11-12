package kotobaya.settings

import kotobaya.server.Database
import kotobaya.server.OperationHandler
import kotobaya.sync.OpApply

/**
 * Handlers for settings and FSRS presets (T212b), keyed by operationId for `AppDeps.handlers`.
 *
 * Every write is an op minted through [opApply] (CLAUDE.md rule 8). Wiring (Main.kt, not this ticket's
 * path): `handlers += SettingsModule(db, sync.opApply).handlers`, with the same [OpApply] the sync module uses.
 */
class SettingsModule(db: Database, opApply: OpApply) {
    private val settings = SettingsApi(db, opApply)
    private val presets = PresetApi(db, opApply)

    val handlers: Map<String, OperationHandler> = mapOf(
        "listSettings" to { settings.list(this) },
        "updateSettings" to { settings.update(this) },
        "listPresets" to { presets.list(this) },
        "createPreset" to { presets.create(this) },
        "getPreset" to { presets.get(this) },
        "updatePreset" to { presets.update(this) },
        "deletePreset" to { presets.delete(this) },
        "optimizePreset" to { presets.optimize(this) },
    )
}
