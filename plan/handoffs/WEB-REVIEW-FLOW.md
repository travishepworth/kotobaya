# HANDOFF WEB-REVIEW-FLOW
Status: DONE (`make check MODULE=web` green)

- Seamless: advance()/send() no longer blank the list when a learning card is due. A timer at `dueFirstAt`+250ms (and after each answer) runs `spliceFetch` (background, no loading state) that splices the server order behind the card on screen. If the user answers before it lands, the next local card shows at once. The server lists a learning card only once due, so no-learn-ahead holds.
- ADR-039: `review/keepGoingDay.ts` is now in-memory (no localStorage, `KEEP_GOING_KEY` removed). Cleared in `ReviewPage.dispose()`, so it holds for the whole visit and across answers, and is gone after restart. A Keep going pressed on Home still carries into the Review visit it opens (today.svelte.ts untouched; it is not an owned path).
- ADR-041(3): BackExtras example rows: headword, reading and chip are `nowrap`/`flex:none`; the gloss ellipsizes first.
- Tests: dueFirst test 1 rewritten (3 cards, learning card due during card 2, no loading/settling phase, never early); keepGoingDay test rewritten (cleared on a new visit, persists within one visit).
- Screenshot 360x800: scratchpad `kanji-back-360x800.png`. It renders the component CSS in a standalone HTML page with long glosses, not the real route.
- No new dependencies. No contract change.
