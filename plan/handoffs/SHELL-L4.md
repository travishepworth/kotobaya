# HANDOFF SHELL-L4

STATUS: DONE
BRANCH: t/SHELL-L4-local-actions
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `shell/api/actions/`: bulkCards (local actions, else Fallthrough), word status get/set/clear, getNote/updateNote/deleteNote, getCard, listCardReviews; all writes through `LocalWrites`
- Attempt 2: `LocalCardQuery` compiles like the server's `CardSearch.compile` (live smart decks as `SmartDeckRef`s, SQLite residual check, C-24 dictionary forms). If a text term's dictionary forms exceed the 3000 guard, the request is forwarded. `bulk_too_large` is counted on live cards.
- `ShellModule`: `single<CardQuery>`. Test harness: ParityDevice registers `CardQuery`, `driver` public, `DictLookup` details via `InstalledDictionary`; RouteTableTest PENDING trimmed

## Acceptance → tests
- Bulk all-or-nothing → `BulkAtomicTest`
- Edit last-writer-wins, pulled delete, re-match collision → `NoteEditLwwTest`
- Second delete mints nothing → `DeleteIdempotentTest`
- Ops validate, out-of-order convergence → `ActionOpsTest`; also `WordStatusTest`, `NoteCardReadTest`
- Negated smart deck, guard forward, live-card count → `BulkQuerySelectionTest`; Koin wiring → `BulkQueryWiringTest`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- `ShellModule.kt` edited (owned for this one change in attempt 1b): one `single<CardQuery>` line plus imports.
- A selection past the dictionary-form guard is forwarded also for `dry_run`, not only for mutating calls, so the preview counts always match what the call would do.
- The ticket expects 4xx for an unknown well-formed id; the contract counts it in `skipped`, and the code follows the contract.
