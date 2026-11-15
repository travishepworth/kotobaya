# HANDOFF AND-DECK-LABELS

STATUS: DONE
BRANCH: t/AND-DECK-LABELS-ui
CHECK: `make check MODULE=android` → green

## Changed
- search/EntryLabelCache.kt (new): HeadwordFetcher (GET /dict/headwords via ApiClient), EntryLabelCache (app-local file filesDir/entry_labels.tsv, in-memory, not synced), `EntryLabeler.then()` chain.
- DeckRepository/DecksViewModel: local labels shown first; words with no label fetched in background (batches <=500), then re-read.
- DI: chain installed dict -> cache in Decks and Search (note label still first in DeckRepository).

## Acceptance → tests
- batches <=500, cached, no refetch → EntryLabelCacheTest.fetchesInBatchesOfAtMost500...
- order installed > cache > placeholder, omitted stay absent → EntryLabelCacheTest.installedDictionaryBeatsCache...

## New dependencies
- none

## For the reviewer
- Cache file is a tab-separated file (org.json is unmocked in unit tests); no dict/ files touched.
- Omitted seqs are not negatively cached: re-requested each deck open (only when still unlabelled).
- BackNavigationTest failed once (Compose idle timeout) in the first run, passed on rerun; likely flaky.
