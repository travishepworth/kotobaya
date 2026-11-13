# HANDOFF C-17

STATUS: DONE
BRANCH: t/C-17-kanji-words
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- `contracts/api/openapi.yaml`: new `listKanjiWords` `GET /kanji/{literal}/words` (dictionary, `read`; `deck_id`, `mine`, `limit` 1..200 default 50, `cursor`; 404/422), schemas `KanjiWord` (allOf `DictEntrySummary` + `decks`), `KanjiWordDeck`, `KanjiWordPage`, and the example `KanjiWordPage`.
- `contracts/api/SUMMARY.md`: added to the checklist and the `deck_item` cross-check row, plus a "C-17 changes" section.
- `clients/`: regenerated (kotlin, python, typescript).

## Acceptance → tests
- listKanjiWords spec with params, ordering, errors, example → `contracts/api/check.sh` (redocly lint + example validation, check_contract.py)
- clients regenerated, no drift → `make check MODULE=contracts` (git diff on clients/)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Decisions the ticket left open: the cursor is bound to literal/deck_id/mine (a mismatch is 422), pages are not a snapshot (clients dedupe by `seq`), and `decks` is ordered by name COLLATE "C" then id.
- `mine` counts live deck items and live vocab notes only, not word status. Note-keyed deck items never match. The literal is not NFKC-normalised.
