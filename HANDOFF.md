# HANDOFF WEB-30

STATUS: DONE
BRANCH: t/WEB-30 (from t/C30-int)
CHECK: `make check MODULE=web` green; `playwright test review-loop --workers=1` 37/37 (CHROMIUM_PATH=/usr/bin/chromium, core/build/npm copied from main checkout, untracked)

## Changed (≤ 6 lines)
- `lib/review/loop.ts`: `loopParts` (until_stop_parts, fallback ready.learning/later.count/ready.review) and `keepGoingAvailable` (fallback: phase goal_reached/done_for_today).
- Review header shows learning / later / review / new from the parts (later/new hidden at 0); sums to the bar total. Home hero breakdown uses the same parts; waiting Home lists later/review/new plus Keep going.
- `today.svelte.ts` C6: refetch at `later_today.next_due_at` in every phase. Review session already refetches/splices at it (wakeTarget, scheduleDueFetch), unchanged.
- `LoopEnd.svelte`: waiting screen shows Keep going iff `keep_going_available`. i18n keys `loop.part.*`. ADR-048 and word audio untouched.

## Acceptance → tests
- Reported case, Home=header, parts sum, C6 on Home while reviewing, no-button wait: `playwright/review-loop.spec.ts` (WEB-30 describe). Fixtures updated to ADR-049 (stop screen has later 0).

## Screenshots (viewed)
web/screenshots/shell/web30-review-waiting-keepgoing-desktop.png, web30-review-header-desktop.png, web30-review-waiting-nobutton-desktop.png, web30-review-waiting-412x915.png, web30-home-waiting-desktop.png (loop-* baselines re-rendered too)

## For the reviewer
- Header counts are hidden below 34rem container width (existing design), so phones see only "n of total".
- No day_ends_at refetch added (SHOULD in C6).
