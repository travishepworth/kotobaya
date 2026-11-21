# HANDOFF LF-X1

STATUS: DONE
BRANCH: t/LF-X1-blackhole
CHECK: `make check MODULE=android` → green except the 2 expected LocalParityTest failures (LF-GOLD)

## Changed
- `acceptance/blackhole/src/test/kotlin/.../blackhole/`: `BlackHole.kt` (virtual network, 3 modes), `SimPhone.kt` (real SyncClient + LocalApi over every route), `Day.kt` (scripted day), `LoopInvariants.kt` (I1–I4), `BlackHoleDayTest.kt`.
- `acceptance/build.gradle.kts`: compiles `blackhole/` into the android unit-test source set (needs android's Robolectric fixtures; runs in `make check MODULE=android`).
- `UiPathNeverTouchesNetworkTest`: `STILL_FORWARDS` now empty (strict; nothing forwards any more).

## Acceptance → tests
- zero network wait, modes a–c → `noUiPathRequestWaitsForTheNetwork*` (3)
- convergence, answers exactly once, one live note per entry → `convergesAfterHealing*` (3)
- I1–I4 on every queue response → `loopInvariantsHoldOnEveryQueueResponse*` (3)
- make check android → see CHECK

## New dependencies
none

## For the reviewer
- Core's invariant checker is private to core tests, so `LoopInvariants` re-implements I1–I4 on the wire shape (plus a Keep-going dead-end probe in `Day`).
- Network is virtual (counters, no sleeping): any UI call to upstream, any wait, or any sync-transport use during a UI request is a finding. Phone B stays offline all day so both phones materialize the same entries and the server merges.
- The gradle hook edits the `:android` kotlin extension from `acceptance/build.gradle.kts` (owned path) rather than `android/build.gradle.kts`.
- Mutation check 1 (reverted): forwarding `stats/summary` upstream made all three `noUiPathRequestWaitsForTheNetwork*` fail ("reached the network").
- Mutation check 2 (reverted): marking one of B's pending `review.logged` ops acked so it never pushed made all three `convergesAfterHealing*` fail (B vs server rows differ). The invariant tests stayed green, as expected.
