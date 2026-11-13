# HANDOFF T602

STATUS: DONE
BRANCH: t/T602-pitch-cards
CHECK: `make check MODULE=core` → green (also `MODULE=web` green)

## Changed (≤ 6 lines)
- `render/Pitch.kt`: per-pattern name label (`<small>heiban|atamadaka|nakadaka|odaka</small>`), trailing `○` particle slot when drop == last mora; given order kept (dictionary order = most common first).
- `BuiltInTemplates.kt` pitch kind: front = expression + kana reading; back = `{{pitch:reading}}`, audio, tail.
- `DefaultCardRenderer.kt` (one line, not owned): marks now only when `options.pitch` (pitch cards no longer force them).
- `assets/card/card.css`: label style. Updated old pitch tests/goldens in `CardRendererTest`/`RenderGoldens`; new `render/pitch/PitchCardTest.kt`.

## Acceptance → tests
- four patterns, small kana multi-pattern, pitch=false → `PitchCardTest`

## New dependencies
none

## For the reviewer (≤ 3 lines)
- Pitch.kt not moved into `render/pitch/` (would need import edits in DefaultCardRenderer). `core/.../render/card.css` doesn't exist; skipped.
- Pitch card with pitch=false shows only the kana reading (from front), no marks, per ticket.
