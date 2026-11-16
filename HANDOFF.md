# HANDOFF SHELL-L9

STATUS: DONE
BRANCH: t/SHELL-L9-dict-gapfill
CHECK: `make check MODULE=android` → green

## Cause (confirmed by reading the scope build)
`worker/.../dict/package/source.py` adds referenced seqs to `top60k` only when dict-refresh builds a release; `dict_refresh.py` builds packages right after `_requeue_decks` merely *enqueues* list-deck rebuilds. So list-deck items (and notes added later) outside the top 60k are not in the package until a new JMdict release. Conversational words rank low in written-frequency lists. The phone default is `top60k`, and a local miss rendered nothing.

## Changed
- `dict/DictGapCache.kt`: sidecar table `dict_gap_cache` (runtime `IF NOT EXISTS`, no .sq, since there are no migrations), negative results, `dict.server_release_id`, counts
- `dict/DictGapFill.kt`: per-run budget 300, batches, pruning, `isDue`; `DictLookup.kt`: L-7 steps 1–4 + authority
- `InstalledDictionary.entry/kanji` = steps 1–2 (all readers incl. review); `DictInstaller` records server release
- `sync/content/ContentInstallWork.kt`: runs gap fill after content install; enqueues when due
- Parity: `JdbcPackageSource`, `DictGapCache`/`DictLookup` registered in `ParityDevice`

## Acceptance → tests
- renders after one run, offline → `DictGapFillTest.aNoteWhoseEntryIsAbsentFromATop60kPackage…`
- nothing dropped → `…nothingReferencedIsSilentlyDropped…`, `offlineRunReports…`
- cache row removed → `aCacheRowIsRemovedWhenTheInstalledPackageGainsTheEntry`

## New dependencies
- none

## For the reviewer
- On a release change, positive rows stay readable and get refreshed (only negatives are dropped). Dropping them would blank cards until the refetch.
- `JdbcPackageSource` detail reads throw: plain JVM has only org.json stubs.
- Counts are exposed through `DictGapCache.counts()`/`serverReleaseId()`. Nothing shows them in the UI yet; that is A7/W2.
