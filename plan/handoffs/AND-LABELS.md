# HANDOFF AND-LABELS

STATUS: DONE
BRANCH: t/AND-LABELS-words
CHECK: `make check MODULE=android` -> green

## Changed (<= 6 lines)
- Deck words: label = note expression, else dictionary headword (new `EntryLabeler` over `InstalledDictionary`), else key; row shows reading + first gloss.
- Search rows: cause was `supporting = hit.kind` ("reading"/"meaning") plus empty `fields` ({} Takoboto notes). `CardHit` now has reading/meaning (fields, else dictionary via `withLabels`).
- Merged search (ADR-026): mode tabs removed. Plain query: dictionary list, rows chipped "In my cards" (+ deck names), then "Your notes" not among them. Card-syntax query (`key:`/`<>=` term, `isCardQuery`) lists cards only; no dictionary rows then.
- Demo glue edited (`DemoScreens.kt`, two `setMode` calls) because `setMode` is gone.

## Acceptance -> tests
- Deck label -> `DeckLabelsTest`; real reading/meaning -> `MergedSearchTest`, `LocalCardSearchTest`; merged order + flag -> `MergedSearchTest`.

## New dependencies
- none

## For the reviewer
- Settings "GET /meta failed: network" x6: not a settings call. `/meta` is the sync coordinator's reachability probe (`ServerReachability`, net/ + sync/, not my paths); `route=` is just the screen showing when an IOException happened while the OS reported online (VPN/server unreachable). Each failed probe queues a report. Suggest throttling `ClientErrors.apiFailure` for `/meta` or not reporting probe failures.
- Labels use `entry()` per seq (3 queries each); fine for 50-row pages.
