# HANDOFF LOOP-WEB2

STATUS: DONE
BRANCH: t/LOOP-WEB2
CHECK: `make check MODULE=web` → green (295 vitest pass; run with `GRADLE=true` to skip the Gradle npmPackage step, per machine limit; core/build/npm/core symlinked)

## Changed (≤ 6 lines)
- web/src/lib/review/messages.ts: quota keys (capped / unlimited / goalReached) + `quotaLine()`; removed unused reviewsOf/reviewsToday/newOfLong
- web/src/lib/review/LoopMeta.svelte (Home): quota line replaces the new bar in goal_reached / done_for_today
- web/src/routes/review/LoopEnd.svelte: quota line replaces the two figure bars; backlog and Keep going unchanged
- web/src/routes/settings/StudySection.svelte: New cards per session row removed
- web/mock/fake-shell.js: local_api_version 2

## Acceptance → tests
- Quota line text (capped / unlimited / goal) → loopPhases "goal_reached", "done_for_today", "done_for_today unlimited"
- Playwright end-quota assertion → review-loop "review done_for_today" (32 pass)
- 360x800 screenshots of Home and review end, both phases → viewed, layout clean; PNGs not committed (web/screenshots not owned, restored to HEAD)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- goal_reached shows "New-card goal reached" as both heading and quota suffix, as specified; it reads repetitive, so say if you want the suffix dropped.

