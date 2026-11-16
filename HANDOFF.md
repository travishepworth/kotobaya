# HANDOFF SHELL-C1

STATUS: DONE
BRANCH: t/SHELL-C1-shell-contract
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- Attempt 2. SHELL.md L-7 rewritten: one per-entry lookup (installed → sidecar cache → online proxy for dictionary reads/createNote only → 404 / `shell_dict_missing`); rendering and C-27 use installed+cache only; §10.7, §5, §8.2 follow
- §4.3 rows: `createDeck` (`list` forwarded, P-1…P-3), `updateNote` re-match collision, `createNote`, `getReviewQueue`; §11 deck row
- §2.5 gate exemptions = L-8 (five ops)
- shell.yaml: optional `ShellDictInstall.gap_fill {cached, missing}`; client regenerated
- parity: `dict_missing` field, scenario `dict-entry-missing`, tag `dict.entry_missing`

## Acceptance → tests
- check green; removed operationId fails → `ops_map_complete`, `selftest_ops_missing_id`
- bridge examples → `bridge_examples_valid`, `bridge_examples_invalid`
- parity seed ops, mandatory scenarios → `parity_seed_ops`, `parity_seed_accepted`, `parity_mandatory_covered`
- cross-check → `shell_md_cross_check`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- ops.json unchanged: `createDeck` stays `local` with a forwarding handler, like `bulkCards`.
- New decision: an installed scope-`all` package is authoritative (miss = 404 / 422, no network after the online attempt); parity harness installs scope `all`, empty cache, offline.
- `createNote` online miss fetches the entry, then mints locally (never forwards the create).
