# HANDOFF T606

STATUS: DONE
BRANCH: t/T606-fsrs-optimizer
CHECK: `make check MODULE=worker` green (770), `MODULE=deploy` green, `MODULE=server` green (final run, under flock)

## Changed (≤ 6 lines)
- Worker `optimizer/` (model, job, store, enqueue): py-fsrs 6.3.2 training, card-wise time split, strict-`<` held-out gate, child hand-off per C-16; registered `fsrs-optimize` in `runner/__main__.py` (and one line in `tests/jobs/test_dict_refresh_wiring.py`, outside owned paths, kind list).
- Server `kotobaya/optimizer/`: `FsrsParamsPublishRunner` (+payload) mints one `fsrs_params.published` via OpApply; started in `Main.kt` (new required `ComposedApp.fsrsPublish`).
- Helm `cron-optimize.yaml` + `optimizer:` values (off by default: `deploy/backup/check.sh` forbids any CronJob in default render).

## Acceptance → tests
- worse -> not published/no hand-off; better -> handed off → `worker/tests/optimizer/test_optimize_job.py`
- one op, version +1, replay idempotent, out-of-bounds rejected → `FsrsParamsPublishRunnerTest`

## New dependencies
- `fsrs[optimizer]==6.3.2` (worker): the optimizer lives in that extra; pulls torch (+CUDA wheels via uv.lock; the Dockerfile's pip will pull the default torch wheel, so consider a CPU index) and pandas. Image grows by ~GBs.

## For the reviewer (≤ 3 lines)
- Choices: reviews = scheduled/learning, not undone, by `card.preset_id`; min 1000 reviews (`OPTIMIZER_MIN_REVIEWS`); latest 20% of cards by first review held out (`OPTIMIZER_HOLDOUT_FRACTION`); `rmse_bins` = 20 equal-width predicted-p bins. py-fsrs trains from defaults and returns defaults below 512 scorable reviews (-> `not_improved`).
- DB-backed `PgStore`/`enqueue` SQL is not exercised by tests (fake store in worker tests).
