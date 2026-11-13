# HANDOFF F-18

STATUS: DONE
BRANCH: t/F-18-dict-examples-fallback
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/routes/dictionary/+page.svelte: `open()` no longer uses `Promise.all`; entry and examples load independently.
- Examples load via new `loadExamples(seq, gen)`, guarded by the same generation counter; its errors are swallowed.
- Examples heading and block render only when examples are non-empty; the "No examples." fallback branch is removed.

## Acceptance → tests
- Examples failure/empty keeps entry visible → none (UI, no test required by ticket)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The client may still show its own error toast when the examples request fails; that does not replace the entry. Left as-is.
- `dict.examplesNone` is now unused in messages.ts; left in place.
