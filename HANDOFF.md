# HANDOFF T210b

STATUS: DONE
BRANCH: t/T210b-web-cards
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- `web/src/routes/cards/+page.svelte`: search, windowed table, selection (rows / all matching = sends `query`), preview, bulk bar.
- `web/src/lib/cards/`: SearchBox (parseQuery error highlight, completeQuery, server 422 shown), CardTable (own windowing, 500-row pages, loads on scroll), CardPreview (renderCard + shared review CardView), BulkBar (dry_run count, then explicit Apply, then matched/changed/skipped), model.ts, messages.ts, api.ts.

## Acceptance → tests
- Manual only (UI); no tests added per CLAUDE.md. Not exercised against a live server.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- No deck column: the Card/NoteSummary API carries no deck, so the ticket's "deck" column is omitted. Preview passes no dictionary entry (note fields only); vocabulary has no tags (no tag listing endpoint; same as decks). No nav link added (layout not owned): page is `/cards`.
- Selecting more than 5000 loaded ids gets the server's 422 shown; "all matching" needs a non-empty query (contract: query minLength 1).
