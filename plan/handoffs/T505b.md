# HANDOFF T505b

STATUS: DONE
BRANCH: t/T505b-grammar-library
CHECK: `make check MODULE=server` → green

## Changed
- server/.../grammar/: GrammarModule, GrammarApi (list/create/get/update/delete via OpApply ops), GrammarSeeder.
- Main.kt registration; WiringAuditTest: 5 grammar CRUD ids removed from 501 allowlist.
- Seed bundled at server/src/main/resources/grammar-seed/ (copy of fixtures/grammar-seed).
- Seeding = `GrammarSeeder.seed(opApply)` (library call, no endpoint; not invoked at startup). Deterministic ids, so twice is a no-op; deleted seeds stay deleted.

## Acceptance → tests
- CRUD round-trip + scopes → GrammarApiTest.crudRoundTripGoesThroughOpsAndScopesAreEnforced
- Seed valid + idempotent → GrammarApiTest.seedIsValidAndSeedingTwiceIsIdempotent

## New dependencies
none

## For the reviewer
- SEED DEFECT (I was told fixtures are read-only): 3 duplicate (jlpt,title) pairs: N2 "cannot help but do" (わけにはいかない/ずにはいられない), N1 "not to mention" (もさることながら/はおろか), N1 "without, formal" (ことなしに/もなしに). Ticket wants unique titles per level; validate.py checks only patterns. My test asserts unique patterns. Fix the fixture, re-copy to resources, tighten the test.
- Detection-rule syntax is not validated (parser is T506). Search uses ILIKE on title||pattern (trigram index) or meaning. Offset cursors.
