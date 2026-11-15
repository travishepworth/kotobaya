# AND-DECK-LABELS — Android deck inspector: fill labels for words missing from the on-device dictionary

- Phase: P9 · Model: sonnet · Risk: med
- Deps: AND-DECK-INSPECT (merged)
- Branch: t/AND-DECK-LABELS-ui
- Owned paths: android/src/**/decks/, android/src/**/search/EntryLabels.kt (and a small new label-cache class next to it), android/src/**/dict/ ONLY for an app-local (non-synced) label cache table/file if needed (say so), android i18n strings, android/src/test/
- Module(s) for CI: android

## Problem (user report on live APK 0.1.17; root cause found)
Deck detail shows many e:<seq> rows without a word (was the raw "e:12345", now "Word not on this device yet"). DeckRepository.labelled() uses note expression, then InstalledDictionary.asLabeler(). The installed dictionary package is scoped "top60k" (contracts/db/DICT_PACKAGE.md:76): freq_rank <= 60000 plus seqs referenced at build time. Deck words outside that (e.g. media/list deck words) have no label offline.

## Do
1. When rendering a deck's words, collect e:<seq> rows with no local label and fetch them online with GET /dict/headwords?seq=... (listDictHeadwords, batches of <= 500, comma list; omitted seqs keep the placeholder) via the existing API client/auth used elsewhere in the app.
2. Cache results app-locally (headword, reading, first gloss) so later views and offline use show them: persist across restarts (small table in an app-local DB or a file; NOT synced, NOT the dictionary package). Cache lookups batched — no per-row queries.
3. Feed cached labels into the same labeler chain (note -> installed dict -> label cache -> placeholder) so search/other screens using EntryLabeler also benefit.
4. Offline or request failure: keep the placeholder, retry next time the deck opens; never block the list on the network (show local labels first, fill in when the fetch returns).
5. Also show reading/gloss for these rows the same way installed-dict rows show them.

## Acceptance
- [ ] Test: missing seqs are fetched in batches of <= 500 and cached; a second load makes no request.
- [ ] Test: labeler order note > installed dict > cache > placeholder; seqs omitted by the server keep the placeholder.
- [ ] make check MODULE=android green.

## Out of scope
Changing the dictionary package scope (worker), server/contract changes.
