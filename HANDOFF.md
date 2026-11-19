# HANDOFF WEB-28

STATUS: DONE
BRANCH: t/WEB-28-web
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- Review bar total = done + `loop.until_stop` (falls back to ready.total); front_furigana passed to core render (core/index.ts, preview, review state).
- `lib/decks/DeckProgress.svelte`: "N of M words learned · P%" + bar from `Deck.progress`; media decks add raw coverage % + shared `lib/ui/CoverageBar.svelte` (also used by media/[id]). Shown on Home, /decks, /decks/[id].
- `routes/review/KanjiParts.svelte` (via BackExtras): kanji tiles, used reading highlighted, others dimmed, links to dictionary search.
- Mock (`web/mock/demo-api.ts`) now fakes progress, kanji_parts, deck coverage.

## Acceptance → tests
- Screenshots 360x800 + 1280 of Home, list deck, reading deck, review back (3 kanji) viewed; temp spec removed, PNGs not committed.

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Core renders furigana as `寿司（すし）` text on the front (no ruby); I pass the flag through only, per contract.
- Kanji tile opens `/dictionary?q=<kanji>` (no stand-alone kanji page exists). Coverage on Home loads per media deck shown (max 6).
- Home hero is legacy in the mock (no `loop`), so the until_stop bar was not seen visually.
