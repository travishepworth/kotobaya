# HANDOFF C-27

STATUS: DONE
BRANCH: t/C-27-materialize
CHECK: `make check MODULE=contracts|core|server|web|cli` → green

## Changed (≤ 6 lines)
- Server: GET /review/queue passes pendingEntries and, for review/write callers, materializes toMaterialize as server device (DeckMaterializer + shared DeckEntryPlan); a new round only if the last round's notes show (≤3).
- Core (additive): `PendingEntry.expressionKanji`/`newKanjiCards`; rule 4 draws an entry as its whole add_kanji group, so nothing unshowable is created.
- Rules (core README "C-27"): empty/invalid card_kinds → default kinds; empty-front kinds skipped; no-kind entries never passed; user-deleted vocab and kanji notes never re-created; window 2·B+16 with B ≥ keep-going batch.
- Contracts: x-emits-ops on getReviewQueue, `to_materialize`, SYNC.md 8.1 note (qualified per 8.4), ADR-032.
- Web and CLI (`keep_going` action, `more_available` state): stream until an empty keep-going fetch.

## Acceptance → tests
- DeckMaterializeTest (9): ops/idempotent/new_per_day/keep going ×6/read-only/deleted note/device race/add_kanji 2-kanji allowance 1 ×5 GETs = 0 notes/kanji delete + card_kinds [].
- QueuePendingGroupTest (core, jvm+js); web keepGoing.svelte.test.ts; CLI test_review keep_going.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A GET that emits ops. Pending query: ~35 ms warm on 35k items with existing indexes; no migration.
- Android (AND-KEEPGOING-FIX) must fill the new PendingEntry fields and follow core README "C-27".
