# HANDOFF D-3

STATUS: DONE
BRANCH: t/D-3-volume-leak-2
CHECK: `make check MODULE=worker` green (757 passed); `make check MODULE=server` green (exit 0)

## Changed
- worker/tests/runner/conftest.py: `pg_server` fixture ran `postgres:17` without tmpfs (image declares VOLUME) -> added `--tmpfs /var/lib/postgresql/data`; cleanup now `docker rm -f -v`.
- server/src/test/resources/dict/make_fixture_dump.sh: same tmpfs (manual script, not part of check).

## Source
- postgres:17 started by worker tests/runner/conftest.py (used also by importers/jpdb_history, jpdb_deck, etc. via reuse); the ~8 bursts per worker run. D-2 wrongly noted "worker has no docker use".

## Acceptance
- Volume count 216 before and 216 after full worker check and full server check. Core/acceptance untouched.

## New dependencies
- none

## For the reviewer
- Existing leaked volumes not removed (216 total); D-2 prune command still applies.
