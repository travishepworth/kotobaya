# HANDOFF T202b

STATUS: DONE
BRANCH: t/T202b-sync-server
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- Attempt 2, architect fixes. Instants are written as `YYYY[Y]-MM-DD HH:MM:SS.mmm+00`, never `Instant.toString`, and parsed with 5-digit years, so every value up to 2^48−1 stores.
- Merge seam: `OpTx.reserveHlc()` plus `emit(…, hlc = h)`, each reservation usable once. The KDoc tells T202c to return `wall(h)` as the loser's `deleted_at` and to emit the merge with `h`.
- `media.created` now emits the media deck's `deck.created` (caused_by_op_id). `invalid_target` on a smart deck maps to `not_applicable`. `MergeUnavailableException` → 503 on every `OpApply` path.
- `AppendOnlyGuard` matches anywhere in the statement, so CTEs are caught. No `!!` left in main code.
- Unchanged from attempt 1: all 45 op types, push/pull, hooks. Main.kt is still not wired (not my path).

## Acceptance → tests
- 12.1/12.2/12.4, raw-push → `ScenarioTest.workedExample12_*`, `RawPushExamplesTest`
- Idempotency/out-of-order/append-only/scope/pull/hlc → as attempt 1 (`OpApplyTest`, `PushHttpTest`, `PullTest`)
- New: `OpApplyTest.instantsUpToTwoToThe48…`, `aReservedHlcIsUsed…`, `aMergePolicyThatReservesItsHlc…` (ticking clock), `…503OnRestPathsToo`, `itemOpsOnASmartDeck…`, `theAppendOnlyGuardAlsoCatchesCtes`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- A media deck's name is the media title cut to 200 code points (the deck schema's maxLength).
- U+0000 and int4 overflow still map to `invalid_value`, as an interim until C-6.
- Merge detection (8.1) is still T202c; until then a colliding write gets 503.
