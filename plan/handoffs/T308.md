# HANDOFF T308

STATUS: DONE
BRANCH: t/T308-notifications
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `android/.../notify/`: `NextAlarmCalculator` (pure), `DueTrigger`/`DueListener` seam, `DueScheduler` (inexact `setAndAllowWhileIdle`), `DueNotifications` (due-count, nudges, stale-sync), `DueReceiver`, `Channels`, `NotifyFeature` (Koin, rationale screen route `notify_permission`, settings section, app-open hook).
- OUTSIDE OWNED PATHS (flagged): AndroidManifest (POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, `.notify.DueReceiver`); one line `kotobaya.android.notify.notifyFeature,` in `di/FeatureModules.kt`; new `res/values/strings_notify.xml`.
- T307: bind `single { ... } bind DueListener::class`. Anyone changing reviews/sync calls `get<DueTrigger>().fire()`.

## Acceptance → tests
- Calculator cases → `NextAlarmCalculatorTest`
- Boot/TIME_SET/TZ reschedule, count text, nudges off, stale once per episode → `DueNotificationsTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- T303c `SyncStatusRepository` does not exist yet: I used a `NotifySignals` seam (`SqliteSignals` reads `sync_state.last_sync_at` and `review_log` read-only); swap when T303c lands.
- "Due" in the notification = due + learning. Review/sync callers must call `DueTrigger.fire()` (not wired: other packages). The onboarding screen route exists; navigating to it is the onboarding ticket's job.
- Robolectric tests start Koin themselves (a JDBC driver inside a sandbox breaks other tests).
