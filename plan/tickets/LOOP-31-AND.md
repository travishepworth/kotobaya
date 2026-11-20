# LOOP-31-AND — Shell queue route: keep_going_new_target, LOCAL_API_VERSION 3

- Phase: P10 local-first · Model: sonnet · Risk: low
- Deps: C-31, LOOP-31-CORE-A
- Branch: t/LOOP-31-AND-loop-shell
- Owned paths (only edit these): android/src/main/kotlin/kotobaya/android/shell/api/review/QueueRoute.kt, shell/api/LocalApi.kt (version constant), android/src/test/kotlin/kotobaya/android/shell/api/ (queue tests), shell parity runner inputs
- Module(s) for CI: android

## Read
- Spec: Clients → Android app
- Contracts: contracts/api/shell/SHELL.md 4.3 `getReviewQueue`, 7.2, 8
- Decisions: ADR-051
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Read and validate the new query parameter like the server (same codes), pass it to the loader's limits; bump `LOCAL_API_VERSION` to 3. Native surfaces (widget, notification) keep calling the loop without a target.

## Acceptance (each bullet → at least one named test)
- [ ] Parity scenarios of C-31 for the target pass against the shell.
- [ ] Unknown/invalid values give the server's `422` pointers.
- [ ] `make check MODULE=android` green.

## Out of scope
Materialize gate and sync triggers (LF-A1).

## Notes from previous attempts
