# HANDOFF FEAT-DICT

STATUS: DONE
BRANCH: t/FEAT-DICT-links
CHECK: `make check MODULE=web` → green (49 tests, svelte-check 0 errors)

## Changed (≤ 6 lines)
- `lib/state/dictionaryLink.ts`: `dictionarySearchHref`, `queryFromSearch`, `dictionaryLinkFor` (seq, else kanji search); `/dictionary?q=` runs that search on load
- New `lib/ui/DictionaryLink.svelte` (icon/button/text looks, i18n `ui.openInDictionary*`); `IconButton` gains optional `target`
- Review: text link under the revealed card, opens in a new tab (session kept; link never handles 1-5)
- Card detail, note editor header, deck items, struggle hot words, unknown words, reader token sheet: entry links
- Struggle hot kanji tiles and confusable words are now links

## Acceptance → tests
- Link present + href helper vitest → `tests/dictionary-link.test.ts` (moved from `src/`, where vitest never ran it)
- make check green → yes

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- The dictionary has no standalone kanji view, so kanji open as a search for the literal (`?q=`), per the ticket's fallback.
- Leech rows (ListRow is already an `<a>` to the note) have no link, because nested anchors are invalid HTML. Their note editor has one.
- Cards/notes tables got no per-row column, to keep conflicts with BROWSE-1/2 small. The link sits in the detail pane.
