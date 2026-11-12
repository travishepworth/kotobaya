# HANDOFF T307 + T309

STATUS: DONE
BRANCH: t/T307+T309-widget-ntfy (two commits)
CHECK: `make check MODULE=android|server|deploy` → green

## Changed (≤ 6 lines)
- T307 `android/.../widget/`: `WidgetData` (pure, + `SqliteWidgetSource` raw read-only), Glance `KotobayaWidget` (2x1/4x2/4x3 responsive), `ReviewTileService`, `WidgetUpdater` (T308 `DueListener`, bound in Koin), `WidgetIntents` (`kotobaya://review`, `kotobaya://word/<entry_seq>`). res `xml/widget_info`, `layout/widget_loading`.
- T309 server `push/NtfySink`: an `EventHub` subscriber (hub untouched). Follows the OpenAPI `/events` text (contract wins over the ticket's base-URL+topic): posts the Event JSON to each non-revoked device's `push_topic` for terminal `job.updated` and `sync.changed` (≤1/min/topic). Only topics under `NTFY_BASE_URL` are posted to (SSRF guard); env `NTFY_BASE_URL`, `NTFY_POST_BASE_URL`, `NTFY_TOKEN`; off when unset. Android `push/`: `KotobayaPushService`, `PushHandler`, `PushRegistrar`, `DevicePushApi`, settings section. Helm `ntfy.yaml` + values.
- OUTSIDE OWNED PATHS (flagged): `AndroidManifest.xml` (widget receiver, tile, push service); `di/FeatureModules.kt` (+2 lines: `widgetFeature`, `pushFeature`); `build.gradle.kts` (deps); new `strings_widget.xml`, `strings_push.xml`; `deploy/helm/check.sh` and one include line in `templates/api.yaml`.
- NOT wired: server `Main.kt` must run `NtfyConfig.fromEnv()?.let { NtfySink(it, jobs.hub, PushTopics.fromDb(db)).start(scope) }`.

## Acceptance → tests
- WidgetData counts/forecast/progress/hot rotation/empty, Glance 3 sizes (data + empty DB), tile intent → `WidgetTest`
- ntfy one POST, unset = nothing, failures don't affect events → `NtfySinkTest`; Android push → notification / malformed ignored → `PushHandlerTest`; helm on/off → `check.sh`

## New dependencies
- `androidx.glance:glance-appwidget:1.1.1` (+ `glance-appwidget-testing` test); `org.unifiedpush.android:connector:3.0.10`

## For the reviewer (≤ 3 lines)
- Hot word = highest-lapse live vocab (core `HotScorer` not wired); same word all study day. `MainActivity` must handle the `kotobaya://review` / `word/` VIEW intents (not mine; review screen is still a placeholder).
- Seams: `PushSyncTrigger` (default no-op; T303c rebinds); sync/review callers must call `DueTrigger.fire()` for widget refresh. `ApiClient` lacks PATCH, so `DevicePushApi` makes its own call to `PATCH /devices/self`. `sync.changed` cannot skip the originating device (event lacks it).
- Backup-failed forwarding stays with the backup job's own `backup.notify.url`.
