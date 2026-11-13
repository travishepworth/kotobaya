# BUG-2 — jpdb deck import dies on title fetch (404); bare numeric ID mapped wrong

- Phase: P7 (live bugfix) · Model: sonnet · Risk: med
- Deps: none
- Branch: t/BUG-2-jpdb-deck
- Owned paths: `worker/src/kotobaya_worker/importers/jpdb_deck/`, `worker/tests/importers/jpdb_deck/`, `web/src/routes/media/new/` (+ any web import-wizard file that parses the deck input, list it in HANDOFF)
- Module(s) for CI: worker, web

## Read
- Contracts: `contracts/db/MEDIA_PIPELINE.md` (jpdb-deck-import job), `contracts/api/openapi.yaml` (media import request: source kind)

## Live report
`importers/jpdb_deck/client/pages.py` fetch_title() GETs https://jpdb.io/visual-novel/3913 (no
`/vocabulary-list`), jpdb returns 404, uncaught → job dies before fetching the list.
`/visual-novel/3913/vocabulary-list` returns 200. Also a bare numeric ID becomes `deck/<id>` (code marks
it UNVERIFIED) → 404. The UI accepted "28" with kind "other".

## Do
- Take the title from the vocabulary-list page (already fetched) — no separate title fetch; if the title
  can't be parsed, fall back to the deck id. A title failure must never fail the job.
- Bare numeric input: reject with a clear validation error (UI and worker) unless a kind is chosen that
  maps to a verified jpdb URL. Accept full jpdb URLs (visual-novel, anime, novel, etc.) and normalise them
  to `<kind>/<id>/vocabulary-list`. Kind "other" + bare number → validation error, not a 404 at job time.
- Worker-side: a 404 on the list page fails the job with a clear message naming the URL.

## Acceptance
- [ ] Test: title parsed from vocabulary-list fixture HTML; title-parse failure falls back to id; no request to the bare `/visual-novel/<id>` URL.
- [ ] Test: URL normalisation table (full URLs of each kind, with/without trailing path, bare number → error).
- [ ] make check MODULE=worker and MODULE=web green.
