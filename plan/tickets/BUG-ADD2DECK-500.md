# BUG-ADD2DECK-500 — adding a search word to a deck returns 500 (Android)

- Phase: P10 · Model: opus (writer-senior) · Risk: med · Gradle-heavy
- Deps: none
- Branch: t/BUG-ADD2DECK-500-android
- Owned paths: android/**, server/src/main/kotlin/kotobaya/decks/** + server tests (only if the same bug exists there)
- Module(s) for CI: android (Gradle lock; `rm -rf /tmp/parity*` after), server if touched

## Read
- Contracts: openapi `addDeckItems` (POST /decks/{deck_id}/items, ~L2774), contracts/api/shell/ops.json (local), SHELL.md
- Decisions: ADR-036

## Facts (scout, 2026-11-19)
- User: on Android, adding a word from search to a deck gives HTTP 500 (web untested).
- Server logs (72h): zero 500s, no POST /decks/*/items → the 500 comes from the on-device local API.
- Android answers it locally: android/.../shell/api/decks/DeckItemRoutes.kt:167. Suspect DeckItemRoutes.kt:225 `checkNotNull(DeckViews.visibleItem(...)) { "item $k missing" }` after minting → IllegalStateException → 500 (unverified).
- Web caller: web/src/lib/ui/deckPicker.ts:77 (toggleMembership) from AddToDeck.svelte / dictionary state.

## Do
1. Reproduce with a failing test using the exact request the SPA sends from search/dictionary (check body shape in deckPicker.ts: entry_seq / note ids, list decks vs user decks, word already in deck, deck with items not yet installed by deck-content snapshot, kanji entry, etc.). Find the real cause; don't just remove the check.
2. Fix; verify the server handler (DeckItemApi.kt) doesn't share the bug (add a server test if it does).
3. Also make the local API log the exception (logcat tag) for any 500 so the next one is diagnosable.
## Acceptance
- [ ] Failing-then-passing test reproducing the 500 with the SPA's request body; parity with server response shape.
- [ ] make check MODULE=android green (and server if touched).
