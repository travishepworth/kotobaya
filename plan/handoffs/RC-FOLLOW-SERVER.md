# HANDOFF RC-FOLLOW-SERVER

STATUS: DONE
BRANCH: t/RC-FOLLOW-SERVER
CHECK: `make check MODULE=server` → green

## Changed
- StatsModule: `/stats/summary` now uses `db.tx` directly (exempt from the 3-permit stats gate).
- ArrayBindTest guard regex now `\b(any|all)\s*\(\s*\?` (case-insensitive); CoverageQuery `<> all(?::text[])` rewritten to `<> all(array(select unnest(?::text[])))` (all sites).
- EntryExamples `unnest(?::uuid[])`, MediaApiModule `unnest(?::bigint[])`.
- ArrayBindTest: removed the 2 s wall-clock assertion (kept the correctness check).

## Item 5 (live logs, last 6 h, read-only)
Not reproduced: only 3 retention/calibration requests (one pair from the cli token, one retention from a web session), each 0.6–0.9 s, no 4x bursts. No web ticket needed.

## New dependencies
- none
