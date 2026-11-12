# HANDOFF T003 (attempt 2)

STATUS: DONE
BRANCH: t/T003-db-schema
CHECK: `make check MODULE=contracts` → green (stub). Real check: `contracts/db/check.sh` → green.

## Changed (≤ 6 lines)
- Merge (SCHEMA §3, §4.2): creation stamps non-default registers; merge is replayed identically on every replica; ops addressed to a loser are forwarded to the survivor.
- `note.entry_seq` is plain LWW; any colliding op merges instead of failing.
- `sentence_grammar_span`: natural primary key, soft `sentence_id`, reversible manual tombstone (`grammar.span_put/removed`).
- Media/list `deck.created` server-only; media deck `id = media_id`.
- Append-only triggers on `review_log`/`change`; earliest undo wins; `utc_offset_min` nullable.
- SUMMARY: "Known trade-offs" section.

## Acceptance → tests
- DDL on Postgres 17 → `check.sh` step 1 + `test/smoke.sql`.
- SQLDelight compiles → `check.sh` step 3; schema parity → step 2.
- Table ↔ op → SCHEMA §4.1, §7.

## New dependencies
- `app.cash.sqldelight` 2.0.2, only in `contracts/db/verify`.

## For the reviewer (≤ 3 lines)
- Finding 1a deviates deliberately: default-valued registers get no clock, so a duplicate's blanks never overwrite.
- Deletes addressed to a merge loser are not forwarded.
- Minors 7 and 8 recorded as trade-offs, not changed. Makefile wiring and CNPG `C.UTF-8` still open.
