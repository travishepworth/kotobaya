# HANDOFF LF-A8b

STATUS: CONTRACT_CHANGE_REQUEST (all 14 ops implemented as SHELL.md says; one contract conflict)
BRANCH: t/LF-A8b
CHECK: `make check MODULE=android` → red only on the baseline: LocalParityTest x2 (goldens not recorded). RouteTableTest is now green (PENDING empty).

## Changed
- `shell/api/{confusables,grammar,media}/`: 14 local handlers through `LocalWrites`; keyset lists (`confusables/Keyset.kt`)
- `LocalApi.kt`: 3 route groups; `notetypes/NoteTypeWrites.kt`: `SEEDS` internal

## Acceptance → tests
- parity, schema, convergence → `ConfusableWritesTest`, `GrammarWritesTest` (`*LocalScenario*`, `minted*Converge*`)
- media via pull-apply → `MediaWritesTest`
- idempotency, refusals → `createTwice*`, `refusalsMintNothing`, `aMergeLoserActsOnItsSurvivor`
- offline, read-your-writes → `LocalWritesOfflineBTest`
- review minors → `RemoteInterleaveTest`, `BuiltInSeedsTest`. Preset `30.0` waived: `JsonNumber` is a Double.

## New dependencies
none

## For the reviewer
- `updateMedia` cannot check the cover blob; falls under 10.21.

## If CONTRACT_CHANGE_REQUEST
CONTRACT: contracts/sync/OPS.md `deck.deleted` (+ server `OpRules.invalidTarget`)
CHANGE: "invalid_target: a pushed op addresses a media deck **whose media is live**". A device `deck.deleted` after its `media.deleted` is then accepted.
WHY: SHELL.md 4.3 has `deleteMedia` mint `deck.deleted` for the media deck. Today the server rejects that op. Every media delete shows a rejected op, and Discard un-deletes the deck.
ADDITIVE: yes
