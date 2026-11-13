# HANDOFF T508a

STATUS: DONE
BRANCH: t/T508a-grammar-capture-web
CHECK: `make check MODULE=web` → green

## Changed
- web/src/lib/grammar-capture/: GrammarCapture.svelte (select span, new/attach, draft rule, server rule test, 422 shown inline), rule.ts, messages.ts (own i18n table `tg`).
- "Saw something weird" button hooked into media reader, media sentences list, dictionary media examples.

## Acceptance → tests
- Flow reachable from a sentence → UI only, no tests (per ticket).

## New dependencies
- none

## For the reviewer
- testGrammarRules needs a point id, so a new point is created (draft rule, idempotent id) on first Test or Save; attach mode appends a draft rule via updateGrammarPoint.
- Span selection uses the browser selection inside one plain-text node (UTF-16 offsets).
