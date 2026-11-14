# HANDOFF AND-TAKO-PERF

STATUS: DONE
BRANCH: t/AND-TAKO-PERF-dupcheck
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- Cause: before the csum filter, `findNotes` did 2 lookups per vocab note: an ankiapi map query and a full `InstalledDictionary.entry()` (3 queries + JSON). Its 4096-entry LRU thrashed on 6.6k notes.
- Fix (`TakobotoAnkiStore.findNotes`): hash the first field only. Headwords come from one batched `EntryLookup.forms()` (dict_entry PK chunks), links from one `AnkiIdMap.noteLinks()` read; hits only get a full view.
- `AnkiContract`: regexes compiled once, plain-text fast path. No index added.
- Other Takoboto paths use indexed lookups or tiny tables.

## Acceptance → tests
- same rows → `DuplicateQueryCsumTest` (2 tests), checksum goldens in `AnkiProviderUnitsTest`, existing store tests
- benchmark → `DuplicateQueryBenchmarkTest` (opt-in `KOTOBAYA_BENCH_DB`, synthetic 60k dict), JVM: miss cold 1379→91 ms, warm 697→55, hit 532→47. Phone was 37,692 ms; not re-measured on device.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Fixed `canonical === row` → id compare. Without `mid`, linked notes were rendered with the wrong model.
- Deck "Takoboto" = inbox id 1, unlisted when user decks exist; notes go deckless (T305c design, not a bug). 食べる was an existing duplicate.
