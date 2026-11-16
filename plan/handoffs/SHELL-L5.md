# HANDOFF SHELL-L5

STATUS: DONE
BRANCH: t/SHELL-L5-local-mining (from t/SHELL-L4-local-actions)
CHECK: `make check MODULE=android` → green (one run hit a flake in ankiapi DuplicateCheckExpressionTest, "No suitable driver"; passed on rerun)

## Changed (≤ 6 lines)
- mining/Routes.kt: all 8 ops registered; RouteTableTest PENDING lines removed
- DictRoutes.kt, DictSearch.kt: L-7 reads over DictLookup (entry/kanji/examples), package+sidecar search, forward-and-merge with replica overlay, `l.` cursors
- CreateNoteRoute.kt: server-identical validation/ordering, L-7 entry resolve (online fetch → cache → local mint), 409/200/422/503
- DictViews.kt, DictPackage.kt, MediaSentences.kt: JSON views, read-only package tables, installed-media examples
- ParityDevice: `server` Upstream, `remote`, `withDictionary`; review/QueueParityTest pending-list assertion updated (outside owned paths: those two scenarios now run whole)

## Acceptance → tests
- Parity createNote duplicate/retry, reading retired, dict-entry-missing → LocalParityTest (20/20 match)
- Search kana/kanji/romaji → DictSearchTest (server's 50-query fixture oracle; no search parity scenario exists)
- CreateNoteOpsTest; SameWordTwiceOfflineTest; DictFallthroughTest

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- createNote follows server NoteApi, not NoteCreator.create (no add_kanji/unmatched path; openapi x-emits-ops); reuses NoteCreator.noteTypeInfo, DuplicateCheck, readingEnabled.
- Offline non-authoritative miss but a live holder exists → answers as duplicate (409/200), not 503.
- Forwarded answers get `kotobaya-local: 1` (LocalApi stamps every handler response; kernel not owned).
