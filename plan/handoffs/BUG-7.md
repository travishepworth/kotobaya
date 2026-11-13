# HANDOFF BUG-7

STATUS: DONE
BRANCH: t/BUG-7-cards-preview
CHECK: `make check MODULE=web` → green (exit 0)

## Changed (≤ 6 lines)
- CardTable: whole row is clickable/focusable (Enter activates); checkbox stops propagation, bulk-only. Label via new `rowLabel` (model.ts): fields → headword → kanjiLiteral → short id.
- CardPreview: fetches DictEntry/Kanji via DictionaryApi (cached in api.ts), passes toDictionary/toKanji to renderCard; loading + error states; new i18n strings.
- routes/cards: headword map filled lazily from preview fetches (no per-row requests).

## Acceptance → tests
- label fallback → web/src/lib/cards/model.test.ts (rowLabel)

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Unopened dictionary rows show the short note id until previewed (no batch lookup exists).

## If CONTRACT_CHANGE_REQUEST
CONTRACT: contracts/api/openapi.yaml
CHANGE: Either add `GET /dictionary/entries?seqs=1,2,3` (max 500) returning `{items: DictEntry[]}` (or a slim `{seq, headword, headwordReading}`), or add optional `headword` to the Note schema returned by listCards for referenced notes.
WHY: getDictEntry is one-per-seq, so headwords for a 500-row page need 500 requests; without it rows show the note id until previewed.
ADDITIVE: yes
