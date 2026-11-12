# HANDOFF C-6

STATUS: DONE
BRANCH: t/C-6-storable-values
CHECK: `make check MODULE=contracts` → green; `make check MODULE=core` → green

## Changed (≤ 6 lines)
- SYNC 2.1 "Storable values": no U+0000 (pattern on all 47 typed strings, tags `^[^\s\u0000]+$`, plus op-level `NO_NUL` walk for open JSON/member names); int4 maxima (deck limits, steps, content_version, weights_version); instant_ms ≤ 253402300799999; match_text ≤ 200
- REST → 422 invalid_value; importers split on ECMA \s, count dropped tags (SYNC 2.1, openapi §3); hlc_last in SYNC 3.2/6.3, SCHEMA 4.4
- Doc minors: u-flag wording, "(push parse MUST)", §9 SET_DUE-on-NEW, replay @throws, validate.py raises on `[\s-z]`/`{,`/`\-`
- 11 examples/invalid + 2 examples/invalid-codec; clients, core embeddings regenerated

## Acceptance → tests
- new rules → contracts/sync/check.sh; core `OpCodecTest.everyInvalidExampleIsRejectedWithTheExpectedReason`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Outside owned paths: added 11 expectation rows to OpCodecTest's map. A recursive no-NUL schema overflowed the stack at depth 500, so it became a codec rule.
- Follow-up T202c: implement NO_NUL in DefaultOpCodec and embed examples/invalid-codec. HUMAN_CHECKS line needed.

Breaking: narrows v1 schemas (SYNC 15 exception). No real data; dev DBs could not store U+0000. Reset dev DBs. T202b keeps interim invalid_value until core NO_NUL lands.
