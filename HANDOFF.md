# HANDOFF OBS-1s

STATUS: DONE
BRANCH: t/OBS-1s-server-logging
CHECK: `make check MODULE=server` → green

## Changed (≤ 6 lines)
- server/Logging.kt (new): `X-Request-Id` accept-or-mint, coroutine `CallMdc` (request_id, token.id/name, device_id), `withCallMdc`, coroutine and uncaught-exception handlers
- App.kt: echo request id; access log (5xx WARN, probes DEBUG); StatusPages logs with MDC and stack trace; Main scope uses the handler
- PushService: duplicate INFO, rejection WARN (op_id, op_type, reason) after commit; DuplicateMerge conflict is WARN
- jobs/JobResultLog.kt + JobWatcher: one line per finished job with counts; failed ERROR, dropped rows WARN, jpdb 0 output ERROR
- Runner loops log ERROR with stack trace; auth logs no longer repeat MDC keys (the target token is now `target_token.id`)
- docs/RUNBOOK.md: "How to check health" grep table

## Acceptance → tests
- request-id echo → ServerTest.requestIdIsAcceptedOrMintedAndEchoed
- op rejection log line → OpOutcomeLogTest
- job zero/drop/failure levels → JobResultLogTest

## New dependencies
- none (logstash encoder was already in use)

## For the reviewer (≤ 3 lines)
- The jpdb ERROR rule: input > 0, and cards_created, notes_existing (vocab and kanji) and word_statuses_set are all 0.
- Job lines come from JobWatcher, so a job that finishes while the API restarts has no line. C-23 endpoint not done.
