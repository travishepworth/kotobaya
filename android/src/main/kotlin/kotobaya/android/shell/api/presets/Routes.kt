package kotobaya.android.shell.api.presets

import kotobaya.android.shell.api.LocalRoute

/** Preset writes (LF-A8, SHELL.md 4.3 / L-10); `listPresets` / `getPreset` live in the identity group. */
val routes: List<LocalRoute> = listOf(
    LocalRoute("createPreset", "POST", "/api/v1/presets") { call -> createPreset(call) },
    LocalRoute("updatePreset", "PATCH", "/api/v1/presets/{preset_id}") { call -> updatePreset(call) },
    LocalRoute("deletePreset", "DELETE", "/api/v1/presets/{preset_id}") { call -> deletePreset(call) },
)
