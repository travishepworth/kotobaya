# JLPT-1a — Worker: load JLPT vocab lists in dict-refresh + deck-build handler

- Phase: P10 (0.1.10) · Model: sonnet · Risk: med
- Deps: C-21 (merged 1578783)
- Branch: t/JLPT-1a-worker-lists
- Owned paths (only edit these): worker/** , fixtures/dict/** (new small JLPT fixture CSVs + README licence note)
- Module(s) for CI: worker

## Read
- Contracts: contracts/db/VOCAB_LISTS.md (all — normative), contracts/db/V3__vocab_list.sql, contracts/db/SNAPSHOT.md (deck-items archive), contracts/db/media/deck-publish.v1.schema.json, openapi JobPayloadDeckBuild / JobResultDeckBuild / JobResultDictRefresh.vocab_lists
- docs/DECISIONS.md ADR-022

## Live context
0.1.9: GET /deck-lists reports 0 for every JLPT list; app.job has never had a deck-build row handled; worker runner/__main__.py:28-38 registers no deck-build handler. Dictionary is loaded (218k entries).

## Do
Implement VOCAB_LISTS.md §2–§4.4 and the worker side of §5.1:
- JLPT source (pinned CSVs, on by default, sha256-pinned per §2) loaded inside dict-refresh into ref.vocab_list / vocab_list_item / vocab_list_unmatched with the §3.2 matching rule and §3.3 accounting; dict-refresh result gains `vocab_lists`.
- After a refresh, enqueue rebuilds of live list decks per §3.4.
- `deck-build` handler: resolve list_key (§4.2), items and order (§4.3), write the deck-items archive and the `deck-publish` child row per §5.1; `empty_list` → fail, never publish empty.
- Register the handler in runner/__main__.py.

## Acceptance (each → named test)
- [ ] Runner test: every kind in API_JOB_KINDS that the worker owns has a registered handler (would have caught the missing deck-build).
- [ ] Mapping-count test on a small JLPT fixture (e.g. 20–30 N5/N4 rows incl. one unmatched, one ambiguous, one shadowed by an easier level) against fixtures/dict: source_rows = matched + unmatched + …(per §3.3 invariant); nothing silently dropped.
- [ ] deck-build on the fixture produces a deck-items archive that passes `contracts/db/media/validate.py` and a deck-publish payload that passes the deck-publish schema; item count equals the list's matched count.
- [ ] Empty list → job fails `empty_list`, no child row.
- [ ] make check MODULE=worker green.

## Out of scope
Server runner and /deck-lists counts (JLPT-1b, parallel). Helm/values (orchestrator).
