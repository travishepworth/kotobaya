# HANDOFF C-7

STATUS: DONE
BRANCH: t/C-7-settings-keys
CHECK: `make check MODULE=contracts` → green; `make check MODULE=core` → green

## Changed (≤ 6 lines)
- SCHEMA.md §6: added `scheduler.kanji_before_vocab` (bool, false) and `scheduler.mined_priority` (int4, 0).
- §6.1: any key is stored; only well-known keys are type-checked, and only by updateSettings (422 `/values/<key>`). Synced bad values are read as the default.
- §6.2: mined_priority = `decklessPriority`, on the deck.priority scale (ascending; ties round-robin, deckless last). kanji_before_vocab is the `add_kanji` ordering fallback; a deck's explicit value (even `off`) wins. It never creates cards.
- QueueBuilder.kt (KDoc + additive member): the key is renamed to `scheduler.mined_priority`; added `QueueInput.decklessAddKanji: AddKanjiMode = OFF`; rule 4 and the `addKanji` docs now cover the fallback.
- db/ and core/ SUMMARY.md: added C-7 sections. core/README: the follow-up is marked done. openapi is unchanged because it lists no keys.

## Acceptance → tests
- Contract/KDoc change, default keeps callers compiling → `make check MODULE=core`, `MODULE=contracts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The T212b ticket says "unknown key 422", which contradicts the contract: unknown keys are stored, and only well-known keys get 422.
- The platform (loader) resolves the effective add_kanji per deck and for the deckless source; core does not read settings.
