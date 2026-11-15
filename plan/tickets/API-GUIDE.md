# API-GUIDE — Task-oriented API usage guide

- Phase: P10 CLI daemon · Model: haiku (writer-light) · Risk: low
- Deps: none
- Branch: t/API-GUIDE
- Owned paths (only edit these): docs/API-GUIDE.md (new); one link line each in cli/README.md and docs/RUNBOOK.md
- Module(s) for CI: none (docs)

## Read
- docs/CLI-DAEMON.md IN FULL. It is the source of truth (human-approved 2026-11-15). Follow it exactly; where it is silent, pick the most reversible option and note it in HANDOFF.
- plan/handoffs/T604*.md (existing cli/), contracts/api/openapi.yaml for the endpoints used (read-only unless you are the architect on ARCH-CARD-TEXT).

## Do
User request (2026-11-15): an easily referenceable usage guide for the API: what to run to do X.
- docs/API-GUIDE.md: setup (base URL, mint a PAT in Settings > Tokens, scopes, `export KOTOBAYA_URL/KOTOBAYA_TOKEN`, auth header), then short recipes, each with: the curl command (copy-paste, using $KOTOBAYA_URL/$KOTOBAYA_TOKEN, jq for output), the CLI equivalent if one exists (`kotobaya …`), required scope, and a one-line expected result. Group by task:
  review (get queue, answer, undo), notes/cards (create note from dictionary entry, find cards with the search syntax, suspend/bury), decks (list, create user deck, add/remove items, enable a JLPT/frequency list deck from /deck-lists, set priority), dictionary (search, entry, kanji), settings (read, set e.g. scheduler.mined_priority), imports/media/jobs (jpdb import, media import, job status), admin (dict refresh with freq_lists, FSRS optimize, health), sync/devices/tokens (list, revoke).
- Every endpoint and body field must come from contracts/api/openapi.yaml (verify; don't invent). Link the operationId for each recipe. Add a short 'search syntax' cheat sheet from contracts/core/search-examples.tsv.
- A table of contents at the top; keep each recipe ≤ 10 lines.
## Acceptance
- [ ] Every curl path/method/field exists in openapi.yaml (state in HANDOFF how you checked, e.g. a script listing operationIds used).
- [ ] Links added to cli/README.md and docs/RUNBOOK.md.

## Notes
- No new dependencies expected (stdlib tomllib/socket/os.mkfifo + the existing generated client). Any addition goes in HANDOFF.md with a reason.
- Never hand-edit clients/; regenerate.
