# BUG-11 — jpdb deck import fetches the overview page and silently imports 0 words

- Phase: P10 (0.1.10 hotfix) · Model: sonnet · Risk: med
- Deps: none
- Branch: t/BUG-11-jpdb-deck-url
- Owned paths (only edit these): worker/src/kotobaya_worker/importers/jpdb_deck/**, worker/tests/importers/jpdb_deck/**, fixtures/jpdb/deck/**
- Module(s) for CI: worker

## Read
- Spec: jpdb deck import section of docs/SPEC.md
- Contracts: contracts/db/SNAPSHOT.md (deck-items archive) only if you touch the archive writer

## Diagnosis (live 0.1.9, verified)
Job jpdb-deck-import for `visual-novel/3913` succeeded with tokens 0, unique_entries 0; worker log `DeckStats(fetched=0, mapped=0, …)`; deck "Fatal Twelve" has 0 deck_item rows. Dictionary is loaded (218k entries) — not a dictionary miss.
- `client/pages.py:123-124` `_first_url` builds `/<kind>/<id>/vocabulary-list` WITHOUT the title slug. jpdb answers that URL with HTTP 200 and the deck OVERVIEW page (0 vocab rows). `/<kind>/<id>/<slug>/vocabulary-list` returns the real list (50 rows/page) and the existing regexes parse it, including next-page links.
- `pages.py:126-147` raises nothing when the first page has 0 rows → job reports success.
- `client/fetch.py:41,67-72` caches pages forever (`max_age=None`); the bad overview page is cached on the worker PVC (/var/cache/kotobaya-jpdb), so a URL-only fix would hit it again.
- Tests missed it: `test_jpdb_deck_client.py:26-30,216` routes the slugless URL straight to the list fixtures.

## Do
1. Build the list URL correctly: keep the slug from the user's URL when present; otherwise fetch the overview page and follow its "vocabulary-list" link (the overview links to `/visual-novel/3913/fatal-twelve/vocabulary-list`).
2. Fail the job (clear error, no publish) when the first list page has 0 entry rows — never publish an empty deck from a jpdb import.
3. Never cache a list page with 0 rows; treat an existing cached page with 0 rows as a cache miss (so the poisoned live cache entry is ignored).
4. Re-importing the same jpdb deck must repopulate the existing (empty) deck/media rather than fail or duplicate — verify the existing behaviour; fix within owned paths if broken, else note it in HANDOFF.

## Acceptance (each bullet → at least one named test)
- [ ] New fixture `fixtures/jpdb/deck/deck-overview.html` (a trimmed real overview page, same licence note as the other jpdb fixtures) served at the slugless URL; `fetch_deck` with a slugless input URL reaches the list fixtures and returns the full word count (mapping-count assert: every fixture row fetched and mapped or reported, none silently dropped).
- [ ] Slugged input URL fetches the list directly.
- [ ] A first page with 0 rows raises (job fails), never returns 0 words.
- [ ] Cached 0-row page is not reused.

## Out of scope
JLPT/frequency list decks (`deck-build`), server code, other importers.
