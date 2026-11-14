# HANDOFF UI-RUBY

STATUS: BLOCKED
BRANCH: t/UI-RUBY-furigana-size
CHECK: `make check MODULE=web` → green; `make check MODULE=android` → red (ThemeApiTest.rubyIsHalfTheBaseButNeverBelowTheMinimum expects 20sp, now 24sp)

## Changed
- design/tokens/tokens.json: ruby scale 0.5→0.6, minRem 0.625→0.75 (12px), minSp 10→12; card ruby-scale 0.5→0.6.
- assets/card/card.css: hand-written rt floor 0.625rem→0.75rem.
- Regenerated via make tokens: web/src/lib/tokens.css, card.css token block, Tokens.kt.

## Acceptance → tests
- rt larger everywhere → token change only; no screenshots taken.
- make check web green → web check (tokens-check included).
- No clipping at 360px / 200% → NOT checked; reasoning only (compaction re-measures after flip; no overflow:hidden).

## New dependencies
- none

## For the reviewer
- Stale text outside owned paths: web/src/lib/ui/messages.ts `sg.type.rubyNote` and docs/DESIGN.md §2.2 still say "half, never below 10 px".

## If BLOCKED
QUESTION: May I update android/src/test/kotlin/kotobaya/android/ui/theme/ThemeApiTest.kt lines 43-45 to expect 24.sp (0.6 × 40sp), or should Android stay at 0.5 (needs a split token)? Also allow editing messages.ts and DESIGN.md §2.2?
