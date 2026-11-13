# HANDOFF — BUG-2 + BUG-3 (branch t/BUG-2+BUG-3-worker-fixes)

Status: DONE. `make check MODULE=worker` and `MODULE=web` green.

## BUG-2
- Title now parsed from the first vocabulary-list page (h1/title, " - jpdb" stripped); falls back to the id; the bare `/<kind>/<id>` URL is never requested. Title helpers swallow errors. A 404 on the list page raises `RuntimeError` naming the URL.
- `normalize_deck_id` (client/pages.py): full jpdb URLs or `<kind>/<id>` -> `<kind>/<id>` (stored jpdb_id stays contract-shaped, without `/vocabulary-list`). Bare number -> ValueError, also in `DeckJob.from_payload`. Chose reject (deck/<id> unverifiable).
- Web: `/media/new` jpdb tab normalises client-side, shows an error for bare numbers, disables Start. Files: `web/src/routes/media/new/+page.svelte`, plus `web/src/lib/media/messages.ts` (new i18n string, outside owned paths, necessary).
- Tests updated/added in `test_jpdb_deck_client.py`. Note: ApiDeckSource's numeric-id branch is now unreachable from the job (ids are always `<kind>/<id>`).

## BUG-3
- `as_utc` in optimizer/model.py; `Review.__post_init__` normalises, `PgStore.load_plan` too. Regression tests: naive timestamps through `run_optimize`, and PgStore with a fake conn. No real-Postgres test: reviewed_at is timestamptz (aware), and pg_server fixtures are not shared with optimizer tests.

No new dependencies. No contract change.
