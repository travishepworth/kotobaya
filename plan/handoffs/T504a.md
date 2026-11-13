# HANDOFF T504a

STATUS: DONE
BRANCH: t/T504a-struggle-web
CHECK: `make check MODULE=web` → green

## Changed (≤ 6 lines)
- web/src/routes/struggle/: hot words + hot kanji (+page), word/[noteId] (hot word page: sentences from media, make sentence card, mnemonic, confusable partners, extra drill), leeches, confusables (confirm/reject); strings in struggle/messages.ts
- Nav: "Struggle" link in routes/+layout.svelte; nav.struggle in lib/i18n.ts
- Drills link to existing `/review?drill=<hot_words|leeches|confusables>` (review page unchanged).

## Acceptance → tests
- make check web green → build/typecheck pass (UI, no tests per ticket)
- Screens reachable from navigation → Struggle nav link

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Extra drill on a hot word drills ALL hot words (no single-word source, F-21); review page ignores entry_seq/query so no per-word drill.
- Hot word page finds the word via listHotWords(limit 200); not found → "not hot any more".
- Strings live in struggle/messages.ts (like decks/messages.ts), since lib/i18n is a single file.
