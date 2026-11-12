# HANDOFF C-6

STATUS: DONE
BRANCH: t/C-6-storable-values
CHECK: `make check MODULE=contracts|core|server` → all green (rebased on main)

## Changed (≤ 6 lines)
- SYNC 2.1 "Storable values": no U+0000 (typed-string patterns + op-level `NO_NUL`), int4 maxima, instant_ms ≤ year 9999 (op values only; derived due_at ≤ ~10099, keep 5-digit rendering)
- Attempt 2: maximum_interval_days ≤ 36500 (derived due_at overflow); list_key/jpdb_id ≤ 200; SCHEMA rule 10 covers derived columns
- NO_NUL position fixed and named in the 5.3 row; importers strip NUL from display text only, skip+count identifiers
- §15: reset dev device replicas/outboxes too; REST 422; hlc_last; doc minors
- OpApplyTest (granted): 253402300799999 stored, 253402300800000 → schema_invalid

## Acceptance → tests
- contracts/sync/check.sh; core `OpCodecTest.everyInvalidExampleIsRejectedWithTheExpectedReason`; server `everySchemaValidInstantStoresAndReadsBackAndLaterOnesAreSchemaInvalid`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Outside owned paths: 12 expectation rows in OpCodecTest's map; the one granted server test.
- T202d: implement NO_NUL in DefaultOpCodec and embed examples/invalid-codec. A HUMAN_CHECKS line is needed.

Breaking: narrows the v1 schemas (an exception under SYNC 15). There is no real data. Reset the dev server DB and every dev device's replica and outbox. T202b keeps its interim invalid_value until T202d lands.
