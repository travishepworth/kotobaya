# HANDOFF C-20

STATUS: DONE
BRANCH: t/C-20-sync-subpage-wording
CHECK: `make check MODULE=contracts` → green (no client drift)

## Changed (≤ 6 lines)
- SYNC.md 6.1: new "Sub-pages (C-20)" block: client MAY apply a page as consecutive sub-pages, one transaction each, `pull_seq` = last `seq` of the sub-page; step 0 once per page; crash/stop rules; worked example (500 ops, sub-pages of 200).
- SYNC.md 9.3: replay MAY be deferred to the end of the triggering transaction (page or sub-page), never later.
- SYNC.md 7.8, 10.3 step 2, 11, 17 ("App killed mid-pull"): cross-references.
- SUMMARY.md: C-20 change-log note.

## Acceptance → tests
- Wording matches BUG-8 → compared with `SyncEngine.pullAll`/`applyChunk` KDoc (read only); no test, wording only.
- contracts green → `make check MODULE=contracts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Beyond the ticket's list: 6.1 requires `since` to be read from stored `sync_state` after an interruption, and purge/`last_sync_at` to wait for the last sub-page. BUG-8 code already does both.
- 6.1 also names locally derived `word_status` values as deferrable to sub-page end (BUG-8 does this); 10.3 and 17 got cross-references too.
- `tools/model.py` still applies a page in one transaction (not changed; no sub-page scenario added).
