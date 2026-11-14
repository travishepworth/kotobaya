# WORKER-PROGRESS — media import looks stuck: per-page progress, rollout-safe job release

- Phase: P9 hotfix 0.1.11 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/WORKER-PROGRESS-import
- Owned paths (only edit these): worker/src/kotobaya_worker/ (runner/, jobs/jpdb deck import + jpdb source pages.py/fetch.py), worker/tests/, deploy/helm/kotobaya/templates/worker.yaml, deploy/helm/kotobaya/values.yaml (worker section only)
- Module(s) for CI: worker (and deploy if chart touched)

## Evidence (live 0.1.10)
User reported "import media gets stuck". jpdb-deck-import visual-novel/3913: progress 0 / "fetching deck" for 5+ min, no log lines. Scout: non-numeric jpdb_id → HTML scrape (pages.py:164-186), one page per 2 s throttle (fetch.py:15,86), up to 1000 pages; progress only 0.0 / 0.5; no per-page log. It also sat queued 4 min behind dict-refresh (single serial runner, FIFO). On rollout, SIGTERM only sets a stop flag; the pod is SIGKILLed after the default 30 s grace, and the job stays `running` until reclaimed by heartbeat staleness (300 s, core.py:86,185-214).

## Do
1. HTML scrape path: update progress_message per page ("fetching deck: page N (M words)") and progress within the 0..0.5 band (unknown total: asymptotic or based on "Next page" offsets if the total is shown); throttled DB writes (≤1/2 s ok). INFO log every 10 pages + a summary line on completion (match OBS-1w style).
2. Graceful shutdown: on SIGTERM while a job runs, if the handler can't finish within a budget (e.g. 20 s), release the job back to `queued` (attempts not consumed, lock cleared) so another runner picks it up immediately; cache makes resume cheap. Handlers that are long (dict-refresh, jpdb scrape) must check the stop flag between pages/stages. Set worker terminationGracePeriodSeconds (default 45) in the chart.
3. Do not change claim order or add concurrency.

## Acceptance
- [ ] Test: scrape of a fake 3-page deck updates progress_message per page and logs a summary.
- [ ] Test: stop flag during a long handler → job back to `queued`, attempts unchanged, locked_by null.
- [ ] make check MODULE=worker green (and deploy if chart changed).

## Out of scope
Worker concurrency/lanes (FOLLOWUPS), web UI changes.
