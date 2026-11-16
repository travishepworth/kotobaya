# HANDOFF KIND-CHIP-OVERLAP-AND

STATUS: DONE
BRANCH: t/KIND-CHIP-OVERLAP-AND
CHECK: `make check MODULE=android` → green

## Changed
- ReviewScreen.kt: tag row is `CardHeaderRow` (New start, kind chip centre, audio end) in normal flow above the content. New `internal CardFace` (header + CardContent + optional back panel) is what `ReviewCard` now calls, so there is one layout path. Absolute overlays removed.
- CardHeaderOverlapTest composes the REAL `CardFace`, with the WebView swapped for a placeholder via `LocalCardContent`.

## Acceptance → tests
- No overlap → CardHeaderOverlapTest: 320/360/411 dp, font scale 1.0/1.3, with and without New/audio; chip and header bottom <= content top.
- PNGs (Robolectric native, 360 dp, x1.3, placeholder text at the content's top edge), written by the test on each run:
  - /tmp/claude-1000/-home-travmonkey-repos-kotobaya/05f3536e-dc73-4935-83df-766bf71b9d1a/scratchpad/and-chip/new-vocab-audio-360dp-x1.3.png
  - /tmp/claude-1000/-home-travmonkey-repos-kotobaya/05f3536e-dc73-4935-83df-766bf71b9d1a/scratchpad/and-chip/vocab-only-360dp-x1.3.png

## New dependencies
- none (roborazzi already a test dependency)

## For the reviewer
- PNGs use `captureRoboImage` with `roborazzi.test.record` set in-test, because `captureToImage` times out under Robolectric. They are 1x-density renders. The test writes them to a fixed scratchpad path.
- Web part of the ticket not done here.
