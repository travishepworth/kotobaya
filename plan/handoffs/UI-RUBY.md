# HANDOFF UI-RUBY

STATUS: DONE
BRANCH: t/UI-RUBY-furigana-size
CHECK: `make check MODULE=web` → green; `make check MODULE=android` → green

## Changed
- ThemeApiTest.kt: renamed to `rubyIsSixTenthsOfTheBaseButNeverBelowTheMinimum`, expects 24.sp (0.6 × 40sp).
- web/src/lib/ui/messages.ts: `sg.type.rubyNote` now says 0.6×, never below 12 px.
- docs/DESIGN.md §2.2: rt = 0.6 × base, never below 12 px/sp.
- Attempt 1 token/card.css/Tokens.kt changes (ff26cb6) unchanged.

## Acceptance → tests
- rt larger everywhere → token change (ff26cb6); no before/after shots taken.
- make check web green → tokens-check and vitest pass.
- make check android green → ThemeApiTest rubyIsSixTenthsOfTheBaseButNeverBelowTheMinimum.
- No rt clipped at 360px / 200% → checked `web/screenshots/default/review--back/360-light-2.0.png` (revealed card, scale 2.0, 360 px): ruby over 猫 and ね/こ fully visible. The fade at the card bottom is the existing scroll "more below" cue, not rt clipping.

## New dependencies
- none

## For the reviewer
- Only the 360 × 2.0 light revealed shot was inspected; dark theme and 1280+ not viewed.
- docs/design-screenshots not refreshed (not cheap enough to justify here).
