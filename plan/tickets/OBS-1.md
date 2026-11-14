# OBS-1 — Stronger logging so problems surface before the user hits them (user request)

Split: C-23 (contract) · OBS-1s (server) · OBS-1w (worker) · OBS-1c (web + android client error reporting). Risk: med each; no new heavy deps (note any in HANDOFF).

## Goals (all parts)
- Structured logs (key=value or JSON, one line per event) carrying request_id, user/token name, device id and op id where known.
- Every op-apply rejection, duplicate and conflict logged with op id, type and reason (INFO for duplicates, WARN for rejections and conflicts).
- Jobs (import, parse, dictionary, JLPT/deck-build, media) log input and output counts at the end, and WARN/ERROR when output is zero or rows were dropped (unmapped/skipped > 0 → WARN with counts; 0 output from non-empty input → ERROR). The 0.1.9 jpdb import that produced 0 cards must have logged ERROR.
- Unhandled exceptions are always logged with a stack trace (server StatusPages, coroutine handlers, worker runner, Android uncaught handler, web window.onerror/unhandledrejection).
- Client-side errors from web and Android are reported to the server (endpoint from C-23), which logs them at WARN/ERROR with the client version and route/screen. Rate-limited and size-capped; no PII beyond the token name.
- docs/RUNBOOK.md: a "How to check health" section with kubectl logs greps for each of the above.

## OBS-1s — server (owned: server/**, docs/RUNBOOK.md)
Request-id MDC (accept X-Request-Id or mint one, echo it in the response), access log with status and latency, token name and device in MDC, op-apply outcome logging, StatusPages stack logging, job-result logging (counts and the error the worker returned), and SSE/scheduler exception logging. Implement the C-23 endpoint once C-23 merges (or in a follow-up).
## OBS-1w — worker (owned: worker/**)  · Deps: LOGFIX-1 merged
Structured logging config (stdlib logging, JSON or key=value formatter; no new deps), job_id/kind in every record, count summaries and zero/drop WARN/ERROR for every handler, and stack traces in the runner.
## OBS-1c — clients (owned: web/src/**, web/tests/**, android/**) · Deps: C-23 merged, OBS-1s
Web global error handlers plus API failure reporting (batched, deduped); Android uncaught handler plus ApiClient failure reporting (queued when offline, capped), and logcat tags consistent.

## Acceptance
- [ ] Each part: tests for count/zero WARN/ERROR emission (worker), the op rejection log line and the request-id echo (server), and the reporter batching/capping (web vitest). make check green for touched modules.

## OBS-1w extra (LOGFIX-1 review minors)
- fetch.py:100: HTTPError is a subclass of URLError, so 404/403 are retried despite the comment. Re-raise HTTPError before the _TRANSIENT catch.
- fetch.py:122: mkstemp .part files leak after SIGKILL/OOM. Delete stale `<name>.*.part` files older than a threshold on entry.
