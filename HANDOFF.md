# WEB-REVIEW-BACK — DONE

- Back (mouse/browser) during review: `beforeNavigate` cancels a popstate-back when the session has answers and runs the shared `ReviewPage.undo()`; repeated back keeps stepping; nothing to undo leaves. Shell Back uses the same `backUndo()` (3 s rule removed per ADR-048).
- Undo is one path for back, `U` key and menu/button: the undone card returns REVEALED.
- Session kept in `web/src/lib/state/reviewSession.ts`; the /review history entry is tagged via `replaceState({reviewKey})`; returning to that entry resumes the same `ReviewPage` (`pauseSession`/`resume`), no refetch. Fresh nav starts new. Review links (dictionary, kanji, decks) now open in the same tab.
- Follow-up: earlier shots were caught mid-fade (not stuck); re-taken after settling. Specs now assert card/footer opacity 1, grade buttons enabled, and that grading the restored card sends exactly one answer request (5 tests). Deleted unused `reviewBackPolicy.ts` + its test; `backOrder.test.ts` updated (repeated back undoes).
- Tests: `web/playwright/review-back.spec.ts` ( back x3 with undo counts, hotkey+button, dict round trip revealed and front). `make check MODULE=web` green.
- Screenshots (dir /tmp/claude-1000/-home-travmonkey-repos-kotobaya/f871c8d0-e7b3-4d58-ba92-fd5b8044cb8f/scratchpad/back-shots/): revealed-after-back.png, review-after-dictionary.png, review-front-after-dictionary.png.
- Android: no change needed (shell back calls the same web handler).
- Notes: SHELL.md 6.2 step 2 and `reviewBackPolicy.ts` (now unused) still describe the 3 s rule; contract update needed by architect. New `web/src/app.d.ts` (PageState). Run needs CHROMIUM_PATH=/usr/bin/chromium.
