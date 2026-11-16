# HANDOFF SHELL-L4

STATUS: DONE
BRANCH: t/SHELL-L4-local-actions
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/api/actions/`: bulkCards (local actions, else Fallthrough), word status get/set/clear, getNote/updateNote/deleteNote, getCard, listCardReviews; all writes through `LocalWrites`
- `CardQuery` / `LocalCardQuery` (wraps `LocalCardSearch`) for the bulk `query` selector; bound in `ShellModule` (attempt 1b)
- ParityDevice: `CardQuery` registered, `driver` public, `DictLookup` entry details via `InstalledDictionary`; RouteTableTest PENDING trimmed

## Acceptance → tests
- Bulk all-or-nothing → `BulkAtomicTest` (malformed id: 422 and zero ops; a refused op rolls back the batch)
- Edit last-writer-wins, pulled delete → `NoteEditLwwTest`
- Second delete mints nothing → `DeleteIdempotentTest`
- Ops validate, out-of-order convergence → `ActionOpsTest`; also `WordStatusTest`, `NoteCardReadTest`
- Koin wiring: `query` selector answered locally, no Upstream call → `BulkQueryWiringTest`
- Parity scenarios → orchestrator ticket (proposed: `bulk-cards-local`, `word-status-set-clear`, `note-update-delete`, `card-get-reviews`)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The ticket expects 4xx for one unknown id; the contract returns 200 and counts it in `skipped`, so code and tests follow the contract (only a malformed id is 422).
- The wiring test resolves the handlers' services from the real shell Koin module; the LocalApi it builds uses a test gate, because `AppShellGate` and `SyncBootstrap` are not constructible in a unit test.
