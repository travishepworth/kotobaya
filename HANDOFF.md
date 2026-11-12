# HANDOFF C-7

STATUS: DONE
BRANCH: t/C-7-settings-keys
CHECK: `make check MODULE=contracts` → green

## Changed (≤ 6 lines)
- SCHEMA.md §6: added `scheduler.kanji_before_vocab` (bool, false) and `scheduler.mined_priority` (int4, 0).
- §6.1: any key is stored; only well-known keys are type-checked, and only by updateSettings (422 `/values/<key>`). Synced bad values are read as the default.
- §6.2: mined_priority is `QueueInput.decklessPriority`, on the deck.priority scale (ascending, so higher = later; ties round-robin with the deckless source last). kanji_before_vocab is the fallback `add_kanji` ordering for decks without the key and for the deckless source; it only orders cards, never creates them.
- db/SUMMARY.md: added a C-7 section. openapi is unchanged because it points to §6 and lists no keys.

## Acceptance → tests
- Doc-only contract change → `make check MODULE=contracts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Needed before T205 (core contract, outside my paths): QueueBuilder.kt KDoc names the key `scheduler.deckless_new_priority` and must be renamed to `scheduler.mined_priority`. The deckless source also needs an additive `QueueInput` add_kanji member.
- The T212b ticket says "unknown key 422", which contradicts the contract: unknown keys are stored, and only well-known keys get 422.
- Decision to check: kanji_before_vocab is a fallback, so a deck's explicit `add_kanji` (even `off`) wins over it.
