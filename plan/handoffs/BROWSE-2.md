# HANDOFF BROWSE-1 + BROWSE-2

STATUS: CONTRACT_CHANGE_REQUEST (BROWSE-1) · DONE (BROWSE-2)
BRANCH: t/BROWSE-1+2-browse
CHECK: `make check MODULE=web` → green (core/server untouched)

## Changed (≤ 6 lines)
- BROWSE-1: no code. Root cause: referenced vocab notes store `fields = {}` (render from dictionary), so `note.search_text` never holds 食べる; core parser and SQL behave exactly as the contract says.
- BROWSE-2: `web/src/lib/state/noteRows.ts` (groupByNote, row selection); `/cards` shows one row per note, with per-card chips (open card, own checkbox, kind/state/due/lapses tooltip).

## Acceptance → tests
- BROWSE-2 grouping + paging boundary → `web/tests/cards/noteRows.test.ts` ("collapses the reading and meaning card…", "paging boundary…")

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Grouping runs over every loaded page: a sibling card on a later page joins the existing row. Duplicate card ids are deduped (last values win).

## If CONTRACT_CHANGE_REQUEST
CONTRACT: core/src/commonMain/kotlin/kotobaya/contracts/search/Search.kt (`SearchTerm.Text` KDoc), plus SCHEMA.md 4 "Notes:" search recipe
CHANGE: Text also matches when `n.entry_seq IS NOT NULL` and some `ref.dict_entry_form` of that seq (kanji or reading, live forms) has `form`/`norm` containing the pattern (norm: query NFKC + katakana→hiragana). Device: same via its ref tables.
WHY: dictionary/jpdb/deck vocab notes are unsearchable by word.
ADDITIVE: yes (widens matches)
