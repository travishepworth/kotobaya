# BUG-3 — FSRS optimizer always fails: naive datetimes into py-fsrs

- Phase: P7 (live bugfix) · Model: sonnet · Risk: med
- Deps: none
- Branch: t/BUG-3-optimizer-tz
- Owned paths: `worker/src/kotobaya_worker/optimizer/`, `worker/tests/optimizer/`
- Module(s) for CI: worker

## Read
- Contracts: `contracts/db/FSRS_PUBLISH.md`, `contracts/db/optimizer/`

## Live report
optimizer/job.py:118 decide → fsrs/scheduler.py:262 raises `ValueError: datetime must be timezone-aware
and set to UTC`. Datetimes passed to review_card are naive (from DB). All 3 attempts failed when the CronJob
was run once by hand; nothing published.

## Do
- Normalise every datetime read from the DB (and any derived ones) to aware UTC at the read boundary
  (naive → assume UTC per CLAUDE.md rule 9; aware non-UTC → convert). Audit the whole optimizer path
  (training, holdout eval, decide) for other naive datetimes.

## Acceptance
- [ ] Regression test feeding naive DB timestamps (as psycopg returns for `timestamp without time zone`, or
      a fake row source producing naive datetimes) through the full job path up to decide — passes, no ValueError.
- [ ] Test against the real Postgres fixture if the job reads via SQL (pg_server fixture exists).
- [ ] make check MODULE=worker green.
