# HANDOFF SHELL-L4

STATUS: DONE
BRANCH: t/SHELL-L4-local-actions
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/api/actions/`: bulkCards (local actions, else Fallthrough), word status get/set/clear, getNote/updateNote/deleteNote, getCard, listCardReviews; all writes through `LocalWrites`
- `CardQuery` / `LocalCardQuery` (wraps `LocalCardSearch`) for the bulk `query` selector
- ParityDevice: `CardQuery` registered; `DictLookup` entry details via `InstalledDictionary`; RouteTableTest PENDING trimmed

## Acceptance → tests
- Bulk atomic → `BulkAtomicTest` (malformed id 422 + zero ops; mint refusal rolls back batch)
- LWW edit / delete → `NoteEditLwwTest`
- Delete idempotent → `DeleteIdempotentTest`
- Ops valid, out-of-order convergence → `ActionOpsTest`; also `WordStatusTest`, `NoteCardReadTest`
- Parity scenarios → none added (contracts/); propose: `bulk-cards-local`, `word-status-set-clear`, `note-update-delete`, `card-get-reviews`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Contract: a well-formed unknown card id is `skipped` (200), not 4xx; test follows contract. ShellModule (not owned) needs `single<CardQuery>{ LocalCardQuery(get(named("syncAppDriver")), get(), get()) }`; until then a `query` selector is forwarded.
- Core finding: `word_status.updated_at` depends on op arrival order (registers converge).
