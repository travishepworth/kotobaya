# LOOP-31-API — Server: keep_going_new_target on getReviewQueue; regenerate clients

- Phase: P10 local-first · Model: sonnet · Risk: low
- Deps: C-31, LOOP-31-CORE-A
- Branch: t/LOOP-31-API-loop-api
- Owned paths (only edit these): server/src/main/kotlin/kotobaya/review/ (ReviewApi.kt, QueueLoader.kt), server/src/test/kotlin/kotobaya/review/, clients/ (regenerate only)
- Module(s) for CI: server, contracts

## Read
- Spec: Public API and scripting
- Contracts: contracts/api/openapi.yaml `getReviewQueue`
- Decisions: ADR-051
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
Parse and validate `keep_going_new_target` exactly as openapi says (errors in the one `422`), pass it as `QueueLimits.keepGoingNewTarget`; `keepGoingNewBatch` stays the request `limit`. Materialization rounds unchanged (only the served build). Regenerate clients.

## Acceptance (each bullet → at least one named test)
- [ ] API test: goal 40, 40 introduced, `keep_going=true&keep_going_new_target=80` serves new cards; at 80 introduced with nothing due → `loop.phase = goal_reached`, `keep_going_available = true` while new cards exist, `empty` and false when none.
- [ ] Without the parameter every existing review API test passes unchanged.
- [ ] `make check MODULE=server` and `MODULE=contracts` green (no client drift).

## Out of scope
Web, Android, CLI daemon.

## Notes from previous attempts
