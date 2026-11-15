# WEB-DECK-INSPECT — Deck inspector: status filters + per-word and bulk actions on deck detail

- Phase: P9 · Model: sonnet · Risk: med
- Deps: none
- Branch: t/WEB-DECK-INSPECT-ui
- Owned paths (only edit these): web/src/routes/decks/[id]/, web/src/lib/state/decks.svelte.ts, web/src/lib/components/ (only new shared pieces, e.g. a WordStatusActions component; you MAY extract the Known/Ignore/Mine actions from routes/media/[id]/read/TokenSheet.svelte and UnknownWords.svelte into it and reuse it there), web i18n strings, web tests
- Module(s) for CI: web

## User request (2026-11-15)
"The deck inspector (Decks tab -> the list) should give filtering options (known, unknown, new, etc.) and actions on the words, similar to viewing the deck from Reading (mark as known, etc.)."

## Today
routes/decks/[id]/+page.svelte + DeckItems.svelte list items via DeckItemsState (decks.svelte.ts:191, listDeckItems) with remove/add only. No status filter, no word actions.
Reading has TokenSheet.svelte (Mine/Known/Ignore) and media/[id]/UnknownWords.svelte (status=new list, "mark known") via reader.svelte.ts / media.svelte.ts -> wordsApi.setWordStatus (PUT /words/{seq}/status => word.status_set).

## API (exists; no contract change)
- GET /decks/{deck_id}/items (listDeckItems): status=<comma list of WordStatusValue: new,learning,known,never_forget,blacklisted,ignored>, sort=position|occurrence_count|freq_rank, limit, cursor.
- PUT /words/{seq}/status (set), and the clear call for word.status_cleared if the generated client has it (check wordsApi); notes create (Add to my notes) as already used by the dictionary/reader.
- getDeckCoverage for per-status counts if it returns them.

## Do
1. Filter chips on deck detail: All · New/unknown (new) · Learning · Known (known+never_forget) · Ignored (blacklisted+ignored). Server-side via `status`; keep cursor paging. Show counts per chip if coverage/a cheap call provides them, else omit counts.
2. Sort: deck order (position) · frequency (freq_rank) · occurrences (media decks).
3. Each row shows its status badge and actions: Mark known, Never forget, Ignore, Blacklist, Reset (clear override -> auto status), Add to my notes (when no note; reuse the reader's "Mine" path), Open in dictionary, Remove from deck (user decks only, existing).
4. Bulk: checkbox select + "select all on this page"; apply Mark known / Ignore / Reset to the selection (one status call per word is fine; show progress, report failures, no silent drops).
5. After an action, the row updates (or leaves the current filter) without a full reload; chip counts refresh.
6. Rows keyed n:<note_id> (custom note, no entry_seq) have no word status: hide status actions for them.
7. Reuse the reader's action component/state rather than a second implementation; no domain logic in TS beyond calling the client.
8. All strings via i18n.

## Acceptance
- [ ] Filter chips map to the right `status` lists; switching filter resets the cursor.
- [ ] Bulk action calls setWordStatus once per selected entry_seq and reports failures.
- [ ] make check MODULE=web green.

## Out of scope
Server/contract changes, Android (AND-DECK-INSPECT), reader redesign.
