# OBS-1s2 — server log safety/noise (OBS-1s review minors)

- Phase: P10 (0.1.10) · Model: sonnet · Risk: low · Deps: OBS-1s merged
- Branch: t/OBS-1s2 · Owned paths: server/**, docs/RUNBOOK.md · Module: server

## Do
1. sync/PushService.kt: op reject WARN must not include rejected values — for SCHEMA_INVALID log reason + JSON pointer only (cap/strip the validator message) so note text never reaches logs.
2. jobs/Events.kt: JobResultLog.log after emit, wrapped in runCatching, so a logging failure can't re-emit the terminal SSE event; RUNBOOK notes each replica logs each job result.
3. Runners (DeckPublishRunner, MediaPublishRunner, FsrsParamsPublishRunner, JpdbHistoryApplyRunner, ConfusableSuggester): rethrow CancellationException before `catch (e: Exception)` so shutdown doesn't log ERROR stack traces.
4. clienterrors/ClientErrors.kt prune: trim each limiter state's deques inside the prune so stale states are removed (no full-map scan per request).
5. Access log: 2xx GET /sync/pull and /events at DEBUG (errors stay WARN).
## Acceptance
- [ ] test for 1 (reject line has no value text) and 3 (cancellation not logged at ERROR); make check MODULE=server green.
