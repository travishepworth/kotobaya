# HANDOFF AND-REVIEWERR + AND-DICTFORMS + FEAT-DICT-APP

STATUS: DONE
BRANCH: t/AND-REVIEWERR+AND-DICTFORMS+FEAT-DICT-APP (3 commits)
CHECK: `make check MODULE=android` → green

## AND-REVIEWERR
- `ReviewState.Failed` + Retry on load failure (`ReviewViewModel.onFailure/retry`); `ReviewSession.answer` un-spends the token and rethrows when mint throws (inline `review_error_action` message); advance failure after a committed mint keeps the token (no duplicate op) and shows Failed/Retry.
- onFailure added to Devices, Intake, Search, Struggle, NotifySettings VMs. Not done: AnkiConnect/Dict settings VMs (no error slot).
- Test: `ReviewErrorTest` (load fail + retry; answer fail re-enables card).

## AND-DICTFORMS
- `LocalCardSearch(dictionary=)` passes `SearchContext.dictForms` via `InstalledDictionary.formRows` (null when none installed). Rows are filtered to the collection's distinct entry seqs while reading the cursor; a pattern still matching >3000 entries (e.g. る) drops the dict branch (note text still matches) to keep the bound seq string small. Test: `DictFormsSearchTest`.
- Smart decks: `LocalQueueLoader` never uses the compiler (resolver unwired), so nothing to wire.

## FEAT-DICT-APP
- `nav/DictJump` (one-shot) -> AppNav goes to Search tab -> SearchRoute opens word detail / kanji search. Entry points: review overflow menu (after reveal only, not on card/grade bar), deck item rows, struggle word page; search results/kanji tiles already open detail. No notes/browse screen exists on Android. No Roborazzi shot (manual check).

## New dependencies
- none

## For the reviewer
- >3000-seq cap trades completeness for speed on very broad patterns; DictJump kanji target just runs the existing kanji-tile search.
