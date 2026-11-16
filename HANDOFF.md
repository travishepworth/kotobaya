# HANDOFF SHELL-L5b

STATUS: DONE
BRANCH: t/SHELL-L5b
CHECK: `make check MODULE=android` → green

## Changed
- ShellResponse.forwarded (ShellHttp.kt); LocalApi omits `kotobaya-local` on it; DictEnv.forward() marks every server answer.
- CreateNoteRoute: source_sentence_id/source_media_id reject JSON null (422).
- DictSearch: package page keys keep the package order (monotone grades, last item = pkg cursor); in-memory GapEntryIndex per (cache generation, release) used by search and listKanjiWords (one table scan, one presentEntries call).
- DictGapCache: `generation`, `snapshot()` memo, `scans` counter.
- MediaSentences: json_each/json_extract pre-filter, falls back to all media sentences without json1.

## Acceptance → tests
- 1–5 → MiningFollowUpsTest (forwarded header, null ids, 3000-entry paging + one scan, media spacing)

## New dependencies
- none

## For the reviewer
- Edited ShellHttp.kt (not listed as owned) for the flag, as the ticket required.
- InstalledDictionary is not owned, so the package's per-item grade is reconstructed (clamped to its cursor), not read.
