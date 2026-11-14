# LOGFIX-1 — Worker: real bugs found in the live logs (run 20 sweep)

- Phase: P10 (0.1.10) · Model: sonnet · Risk: med
- Deps: JLPT-1a merged (both touch dict_refresh)
- Branch: t/LOGFIX-1-worker
- Owned paths: worker/**
- Module(s) for CI: worker

## Do (from plan/FOLLOWUPS.md "Run 20 live sweep")
1. dict-refresh failed once with "ChecksumMismatch: kanjidic2.xml.gz sha256" (a later run succeeded). Find out whether upstream republishing can make a pinned or recorded checksum fail every refresh. Handle it correctly: a transient partial download → retry; legitimately changed upstream content → a new release, not a permanent failure.
2. fsrs-optimize failed once with "datetime must be timezone-aware and set to UTC". Find the naive-datetime path (job params or defaults) and normalise it. Add a test.
3. BUG-11 minor (1): pages.py:142 caches the jpdb overview page without the `accept` (link-regex) check, so a challenge page poisons the cache and remap_only stays on forever. Check before caching. Add a test.
4. C-21 minor (2): a configured jlpt source with only a header row → source_rows 0 succeeds and empties the lists. Fail it with invalid_source. Add a test.
5. BUG-11 minor (2): deck_slug treats any 3rd path segment (/anime/5370/stats) as a slug; accept only real slug segments.

## Acceptance
- [ ] A named test for each of 2, 3, 4 (and 1 if the code changes). make check MODULE=worker green.

## Added from the JLPT-1a review (minors)
6. dict_refresh.py:243: on the resume path, `_requeue_decks` only runs inside `if need_extra:`. Always call it (dedupe makes a repeat a no-op).
7. vocab.py:603: `jlpt_provenance` compares only the n5 URL to decide default vs custom. Compare all five URL/sha pairs with JLPT_DEFAULT.
8. decks/build.py:291: the unchanged path skips `ctx.progress(1.0)`.
