# HANDOFF ARCH-CARD-TEXT

STATUS: DONE
BRANCH: t/ARCH-CARD-TEXT
CHECK: `make check MODULE=contracts` → green · `core` → green · `server` → green

## Changed (≤ 6 lines)
- Contract `core/.../contracts/render/CardTextRenderer.kt`: `TextRenderInput`, `CardText`, the per-kind table, line rules, pitch line (`たべꜜる`), fallback
- `core/.../render/DefaultCardTextRenderer.kt` (new); field resolution shared via `DefaultCardRenderer.resolveField` (HTML goldens unchanged)
- `openapi.yaml`: optional `QueueItem.text` → new `CardText {front, back, reading|null, sentence|null}` + examples; SUMMARY.md section; clients regenerated
- Server `review/QueueText.kt` + `ReviewViews.items`: fills `text` for queue and drill items

## Acceptance → tests
- Goldens, every kind (jvm=js) → `TextRenderGoldenTest` (commonTest, 12 tests incl. edge cases)
- Server, each kind + Takoboto note (fields {} + entry_seq) → `QueueTextTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `back` includes the reading (self-sufficient); `reading` duplicates it. Custom templates use the HTML-render fallback.
- Server dictionary fill = web's mapping (all senses' glosses); Android uses first sense only.
- `contracts/core/README.md` table (not owned) should list `CardTextRenderer`.
