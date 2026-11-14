# OBS-1w2 — worker log noise + robustness (OBS-1w review minors)

- Phase: P10 (0.1.10) · Model: sonnet · Risk: low · Deps: OBS-1w merged
- Branch: t/OBS-1w2 · Owned paths: worker/** · Module: worker

## Do
1. media/pipeline/plan.py: WARN on unmapped tokens only above a ratio (unmapped/tokens > 5%), else INFO (names/onomatopoeia make >0 universal → WARN noise).
2. importers/jpdb_history/handler.py: the plan summary (log_plan) at INFO; only the final log_result carries WARN/ERROR so one import yields one WARN at most.
3. runner/core.py + logs.py: threads (heartbeat, pools) start with contextvars.copy_context().run so records carry job_id/kind.
4. logs.py: invalid LOG_LEVEL falls back to INFO with a warning instead of crashing at startup.
5. logs.py excepthook: chain to sys.__excepthook__ (or fix the docstring).
6. One test that runs a real handler end-to-end and asserts the summary line is emitted and the result unchanged.
## Acceptance
- [ ] tests for 1, 2, 4, 6; make check MODULE=worker green.
