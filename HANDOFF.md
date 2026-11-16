# HANDOFF KIND-CHIP-OVERLAP-AND

STATUS: DONE
BRANCH: t/KIND-CHIP-OVERLAP-AND
CHECK: `make check MODULE=android` → green (see reviewer note)

## Changed
- ReviewScreen.kt: new `CardHeaderRow` (New chip start, kind chip centre, audio button end; three weighted slots) placed in the card Column above `CardContent`; removed the three absolute TopStart/TopCenter/TopEnd overlays. Row height is reserved, so the WebView starts below it. Row is omitted when it has nothing to show.
- New test CardHeaderOverlapTest.kt.

## Acceptance → tests
- No overlap, test guards → CardHeaderOverlapTest (widths 320/360/411 dp, font scale 1.0/1.3, New+audio+missing-audio caption): chip and header bottom <= content top.

## New dependencies
- none

## For the reviewer
- The test composes `CardHeaderRow` plus a content Box in a Column (mirrors ReviewCard's structure), not the whole private ReviewCard.
- Audio button also moved into the row (it overlapped content too).
