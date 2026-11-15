# HANDOFF ARCH-CARD-TEXT (attempt 3)

STATUS: DONE
BRANCH: t/ARCH-CARD-TEXT
CHECK: `make check MODULE=contracts` → green (no clients drift) · `core` → green · `server` → green

## Changed (≤ 6 lines)
- `contracts/api/openapi.yaml` `QueueItem.text`: no longer "present on every item"; absent when the note type is unknown to the server (note-type row missing and `card.note.type` not a built-in base type; item still returned) and on older servers; clients treat absent as "no text" per item
- `contracts/api/SUMMARY.md`: same correction
- `clients/` (kotlin, python, typescript) regenerated with `clients/generate.sh` and committed, covering this and the attempt-2 `CardText` description changes
- No core or server code changes in this round

## Acceptance → tests
- Unchanged from attempt 2: `TextRenderGoldenTest` (core, jvm=js), `QueueTextTest` (server)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Commit `150922eb` is contract text + generated clients only.
- FOLLOWUPS (left alone, as instructed): web pitch[0] parity; invalid cloze back; pitch card with no pitch data; plus the attempt-2 items (sentence_translation, core DictionaryData.fromEntry, contracts/core README entry C-25).
