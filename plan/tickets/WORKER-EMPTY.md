# WORKER-EMPTY — jpdb deck import "succeeds" with 0 rows (silent empty media)

- Phase: P9 hotfix 0.1.11 · Model: sonnet · Risk: med
- Deps: WORKER-PROGRESS (same files; branch from main after it merges)
- Branch: t/WORKER-EMPTY-import
- Owned paths (only edit these): worker/src/kotobaya_worker/ (jpdb deck import handler, jpdb source pages.py/fetch.py/api.py, media-publish payload build), worker/tests/
- Module(s) for CI: worker

## Evidence (app.job)
- 11-13 23:31Z jpdb-deck-import visual-novel/3913 ran 1.8 s, status succeeded; child media-publish payload jpdb.vocab=[]; result tokens=0, unique_entries=0, sentences=0; nothing reported dropped. Same id earlier failed twice 09:12Z/09:13Z with HTTPError 404; re-import 11-14 took 610 s → 203,750 tokens, 10,669 entries. Suspect: page cache (JPDB_CACHE_DIR) or a scrape that parsed 0 rows from a page (404/redirect/empty/login page cached?) treated as "no next page".
- 11-14 02:45Z summary: rows_fetched=10931, mapped=10889, out entries=10669, dropped unmapped=42 → 220 rows unaccounted (likely several jpdb vids → same entry).

## Do
1. Find and fix the root cause of the 0-row success (cache poisoning by error/empty pages, parse of a non-deck page, etc.). Never cache a page that parsed to 0 rows or a non-200.
2. Guard: a deck import that fetched 0 vocabulary rows FAILS with a clear error (no media-publish).
3. Summary accounting: add a `merged` (duplicates collapsing onto one entry) counter so in = out + merged + dropped holds; assert it in tests.

4. (user request) Every stage of jpdb-deck-import and its media-publish child sets progress_message + progress in its band (fetch 0–0.5 is done by WORKER-PROGRESS; map/merge, parse/align, write occurrences, publish/upload, TTS if any) and logs one INFO line per stage with counts.

## Acceptance
- [ ] Test: a fake import walks all stages; each stage writes a distinct progress_message and monotonic progress.
- [ ] Test reproducing the root cause (e.g. cached empty/404 page) → import refetches or fails, never succeeds empty.
- [ ] Test: 0 rows → job failed, no media-publish child.
- [ ] Test: summary counts balance with duplicate vids.
- [ ] make check MODULE=worker green.
