# HANDOFF T605

STATUS: DONE
BRANCH: t/T605-stats
CHECK: `make check MODULE=server` -> green; `make check MODULE=web` -> green

## Changed (≤ 6 lines)
- server/.../stats/: StatsModule (6 read-only handlers), StatsQueries (day rows, core-replay annotation, known-word series), StudyDays (per-row offset + rollover bucketing).
- Summary/forecast use review's QueueLoader + core DefaultQueueBuilder; retention/calibration replay each card with the core FSRS (interval before, predicted R).
- Main.kt: `StatsModule(db, sync.opApply.config.clock).handlers` added to composeApp.
- web/src/routes/stats/ (page + messages.ts, SVG charts), one nav entry in +layout.svelte, `nav.stats` in i18n.ts.
- WiringAuditTest.kt: removed the six getStats* ops from the 501 allowlist (outside owned paths; required for green).

## Acceptance → tests
- heatmap bucketing across rollover, non-UTC, undone excluded -> StatsHeatmapTest.reviewsAreBucketedByStudyDayInTheUserZoneAndUndoneRowsAreExcluded
- UI no tests.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Known-words series is replay-reconstructed (lowest-id live vocab note per entry, override by updated_at); last point uses "now" so it equals `current`. Calibration counts only reviews with a defined predicted R (review_count = binned).
- Heatmap `new_cards` = scheduled/learning row with no earlier schedule-feeding row or after a reset (same rule as the queue loader).
