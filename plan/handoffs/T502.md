# HANDOFF T502

STATUS: DONE
BRANCH: t/T502-confusables
CHECK: `make check MODULE=server` → green (worker not touched, not run)

## Changed
- server/.../confusables/: ConfusableScoring (pure), ConfusableSuggester (mints confusable.suggested via OpApply; startup + every 6h), ConfusableApi/Module (listWordConfusables, listConfusables, setConfusable).
- Main.kt: module registration, ComposedApp.confusables field + start line. WiringAuditTest: 3 ops removed from allowlist.
- Tests: ConfusableScoringTest (each reason + negatives), ConfusableApiTest (idempotency, decisions never flipped, list/set, scope).

## Acceptance → tests
- Pure tests per reason → ConfusableScoringTest
- Idempotent, never flips decision → ConfusableApiTest.suggestionsAreIdempotentAndNeverFlipADecisionBack
- API list/set incl. scope → ConfusableApiTest (3 tests)

## New dependencies
- none

## For the reviewer
- Suggestions run server-side in Kotlin, not as a worker job: a worker job would need a new internal job kind (contract change) and cannot mint ops. So no worker or runner __main__.py edit (no conflict with T506a).
- Heuristics: homophone = same primary reading; look-alike = >=2 shared KRAD components and Jaccard >=0.5 (kanji notes, and vocab differing in one kanji); meaning = shared normalised gloss in first 2 senses; generic buckets are skipped.
