# HANDOFF AND-DECKCONTENT

STATUS: DONE
BRANCH: t/AND-DECKCONTENT-install (rebased on main @ 4f2da0ef)
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- `sync/content/SnapshotReader.kt`: copy of the server's reader (no server types). `ContentInstaller.kt`: follows SNAPSHOT.md 7 — SHA-256, full validation, header vs row, one transaction, `installed_content_version`. Rejected hashes are kept in `sync_state`; errors log under `KotobayaContent`.
- `sync/content/ContentInstallWork.kt`: WorkManager job (CONNECTED, backoff). Queued after each completed sync and at startup when something is pending (`SyncFeature.kt`).
- `decks/DeckRepository.kt`: `DeckSummary.content` = READY/NOT_PUBLISHED/DOWNLOADING/FAILED. Conflict with AND-LABELS resolved; its labeler is kept.
- `decks/ui/DecksScreen.kt` + `strings_decks.xml`: list rows show the state ("Downloading…", "Not ready yet", "Couldn't download deck contents"). The detail page shows a banner, with Retry when FAILED, and an empty word list shows the state instead of "No words".
- Retry clears the rejected hash and queues ContentInstallWork (`DecksViewModel.retryContent`, `DecksFeature.kt`). The decks screen reloads after each install.

## Acceptance → tests (`ContentInstallerTest`)
- Install, counts match header, re-run no-op → `deckItemsAndMediaContentInstallWithHeaderCountsAndRerunIsANoOp`, `contractExampleArchivesInstall`
- Hash/header mismatch → `hashMismatchInstallsNothingAndLogs` (also covers retry), `headerMismatchInstallsNothingAndLogs`, `invalidArchiveInstallsNothing`
- Newer version replaces atomically → `newerContentVersionReplacesOldItemsAtomically`, `aRowThatMovedOnDuringTheDownloadIsNotInstalled`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Media content now installs on the device, which reverses ADR-018 (left for the orchestrator to record).
- A 404 is retried with backoff, not rejected.
- No UI test: only the retry state is tested, at the installer and repository level.
