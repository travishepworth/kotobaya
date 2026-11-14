# HANDOFF OBS-1s + C-23 (server)

STATUS: DONE
BRANCH: t/OBS-1s-c23 (commits: C-23 contract 1bb486d, OBS-1s logging, reportClientErrors handler)
CHECK: `make check MODULE=server` → green (WiringAuditTest included)

## Changed
- OBS-1s: request id (accepted `X-Request-Id` or minted, echoed in header/problems) and token.id/token.name/device_id in MDC for every line (`server/Logging.kt`, App.kt); JSON access log (5xx WARN, probes DEBUG); sync op outcome lines (reject WARN, duplicate INFO); job result lines (`JobResultLog.kt`); uncaught coroutine/thread handlers; RUNBOOK "How to check health" table.
- C-23: `clienterrors/ClientErrors.kt`, `reportClientErrors` wired in `composeApp`. Check order per contract: auth, 12 req/60s per token (429 before body read), 1 MiB (413), 415/400, >20 items (413, checked before schema validation), field validation (422, all errors, one WARN), 120 items/60s all-or-nothing (429), 202 `{accepted}`. Retry-After on 429; one refusal WARN per token per window. Nothing stored.
- One line per item on logger `kotobaya.client_error` (ERROR/WARN by level) with contract fields, control chars/U+2028/9 escaped, route/http_path cut at `?`/`#`; dropped>0 WARN. RUNBOOK: two new grep rows.

## Acceptance → tests
- rate limits, 413 (1 MiB, 21 items), 422, 401, log lines/escaping/token name → `ClientErrorsTest`; op logs → `OpOutcomeLogTest`.

## New dependencies
- none

## For the reviewer
- Refusal WARN uses logger `kotobaya.client_error_refused` (not a child of `client_error`, so item-log filters stay clean). Limiter is in-memory per replica.
- DEL (0x7F) is not escaped (spec lists C0/C1 only).
