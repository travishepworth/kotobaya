# HANDOFF AND-STRICT

STATUS: DONE
BRANCH: t/AND-STRICT
CHECK: `make check MODULE=android` → green

## Changed (≤ 6 lines)
- BlobStore: mkdirs lazy (on `dir`/`partFile` access), not in constructor. ReviewPrefsStore and FileBrowseCache (struggle) take lazy providers, so Koin injection no longer touches prefs/filesDir on main.
- Widget: format strings warmed in the IO block before provideContent (the 1.75 s was Resources.getString format data loaded in composition on main).
- DeckRepository.decks(): one loader.load(), then the core builder re-run per deck on filtered candidates (identical to load(deckFilter)). New test with a binding new_per_day limit.
- LoadGate: doc note, `callers(key)` test hook; LoadGateTest polls it instead of sleep, builds bound loosened (maxInFlight==1 kept).

## Acceptance → tests
- android check green → existing tests + DeckRepositoryTest.deckCountsStillMatchTheFilteredLoaderWhenALimitBinds

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Widget fix is a warm-up, not verified on device; the violation was inside String.format.
- Split counts reuse the unfiltered input, so limits apply per deck exactly as before.
