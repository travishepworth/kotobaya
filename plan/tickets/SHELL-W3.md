# SHELL-W3 — SPA navigation: navigate, back stack with ADR-030, intake params, haptics, theme

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-W1
- Branch: t/SHELL-W3-web-nav
- Owned paths (only edit these): web/src/lib/api/shell/nav.ts (new), web/src/lib/ui/Dialog.svelte, web/src/lib/ui/backStack.svelte.ts (new), web/src/routes/+layout.svelte, web/src/routes/review/+page.svelte, web/src/lib/state/review.svelte.ts (back hook only), web/src/routes/search/+page.svelte, web/src/routes/dictionary/+page.svelte, web/src/lib/ui/theme.svelte.ts, web/tests/shell/**
- Module(s) for CI: web
- Gradle-heavy: no · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 4
- Contracts: contracts/api/shell/SHELL.md (navigation, back)
- Decisions: ADR-030, 036

## Do
- `navigate` → `goto(path)`; `back` → the five-step order of design section 4, answer `back.result`; send `back.state` on every route or overlay change.
- `backStack`: dialogs/sheets register a close handler while open (LIFO).
- Review: `reviewBackPolicy.ts` = port of Android `ReviewBackPolicy` (3 s window, `answered()` resets); undo by back uses the same
  `undoReview` path as the `u` key and shows the undo toast; leaving clears keep-going as today.
- Intake: `/search?q=…&sentence=…` prefills the search; the Add action passes `sentence` into `createNote` where the note type has a sentence field.
- `haptic('tick')` on flip, `confirm` on grade, `reject` on Again (no-op without the capability); `theme` message on theme change and at start.

## Acceptance (each bullet → at least one named test)
- [ ] `reviewBackPolicy.test.ts`: the same cases as Android `ReviewBackPolicyTest` (undo once; second back within 3 s leaves; nothing to undo leaves; new answer re-arms)
- [ ] `backOrder.test.ts`: dialog open → closes; review with undoable answer → one undo call; nested route → history.back; non-Home tab → Home; Home → handled:false
- [ ] `intake.test.ts`: q and sentence survive encoding of Japanese text and `&`/`#` characters

## Out of scope
Native side (A4).

## Notes from previous attempts
