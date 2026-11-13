# FEAT-1 handoff

**Status:** DONE (attempt 1). `make check MODULE=server` and `MODULE=web` green.

## What changed
- Server: `listKanjiWords` in new `server/.../dict/KanjiWords.kt`, registered in `dictHandlers`. One query (`entry_kanji` -> `dict_entry`), keyset cursor bound to literal/deck_id/mine (mismatch 422), `deck_id` orders by occurrence_count desc then freq NULLs last then seq, deleted/unknown deck 404, `mine` = live deck item or live vocab note, `decks[]` ordered `COLLATE "C"` name then id. No 501 allowlist existed (missing handler = 501 stub).
- Test: `server/.../dict/KanjiWordsTest.kt` covers ordering, retired exclusion, mine/deck_id, decks[] exclusions, paging (all three modes), cursor mismatch, 422/404/401.
- Web: `web/src/lib/dictionary/KanjiPanel.svelte` (details, two tabs, deck dropdown, chips, load more, dedupe by seq); kanji in the entry view are buttons; strings in `messages.ts`; `decksApi` added to `api.ts`.

## Notes
- No new dependencies. No contract changes.
- Schema has no user column, so "other users' decks" is not testable (single-user).
- Deck dropdown lists all live decks (smart decks give an empty page by contract).
