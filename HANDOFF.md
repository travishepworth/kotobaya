# HANDOFF T406+T408b

STATUS: DONE
BRANCH: t/T406+T408b-web-reader-sentences
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/lib/reader/` + `routes/media/[id]/read/`: reader (segment picker, furigana toggle, cursor infinite scroll, token popup with entry/status/Mine, override list with delete, re-parse banner).
- `web/src/lib/sentences/` + `routes/media/[id]/sentences/`: All/i+1/i+0 filter, segment, search, unknown-word highlight, audio play, sentence-card form (+ optional vocab note, deck pick).
- Tests: `web/tests/reader-overrides.test.ts`, `web/tests/sentences-note.test.ts`.

## Acceptance → tests
- Override builder concatenation → reader-overrides.test.ts
- Note request builder → sentences-note.test.ts
- Manual (T406): reader → click token (1) → "Split here"/"Join with next"/pick a "Change entry" result (2) saves directly; scope select is preset (default sentence). No separate confirm step.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- SentenceToken has no word status: statuses (reader colouring, unknown-word highlight) are fetched lazily per entry via getDictEntry and cached (lib/reader/status.ts); extra requests per page.
- Deviation: no inline pre-filled editor or parseText preview; popup shows the proposed tokens as text and applies on click, to meet ≤2 clicks. Mine uses default onDuplicate=error; 409 shows a "duplicate" toast.
- Not run against a live server (parse/sentences modules not wired until W-5).
