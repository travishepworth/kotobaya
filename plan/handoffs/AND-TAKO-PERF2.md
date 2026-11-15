# HANDOFF AND-TAKO-PERF2

STATUS: DONE
BRANCH: t/AND-TAKO-PERF2-fast
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Insert cost was in notes/: `DuplicateCheck.byWord` and `liveKanji` read every note. Run-time triggers (`NoteChangeLog`) feed `NoteWordIndex` and `ChecksumIndex`.
- Attempt 2 (major): `catchUp` drops a prepared rebuild when changes after its head were pruned. Seqs are gapless, so it compares the count with the head gap and recomputes in the tx.
- The rebuild read now runs outside the store lock; only the write tx holds it.
- sfld queries (`DuplicateCheck` instance) use the word index.

## Acceptance → tests
- replay add/edit/delete, prune interleaving, log reset + rebuild, sfld → `NoteIndexReplayTest` (7). The interleaving test fails without the fix.
- `TakobotoSendBenchmarkTest` (opt-in), real copy at 1x→4x notes: miss 35→146 is now 1→0 ms; insert 21–31→42–116 is now 15→13 ms.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Tables and triggers are created at run time (no AppDatabase migrations).
- Not done: #2 (log stays until the next csum catch-up) and #4 (trigger DDL has no version).
- One-time build is about 8 s on the phone, in the background and now without the lock.
