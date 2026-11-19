# BUG-ADD2DECK-500 — Svelte effect loop (effect_update_depth_exceeded) shows a "500" page

- Phase: P10 · Model: opus (writer-senior) · Risk: med
- Deps: none
- Branch: t/BUG-ADD2DECK-500-web
- Owned paths: web/** (no web/src/lib/core/** changes)
- Module(s) for CI: web

## Facts (orchestrator, 2026-11-19, from live client-error reports + phone logcat)
- User: adding a word from search/dictionary to a deck shows a 500. It is NOT an HTTP 500: the server's `kotobaya.client_error` log shows `Error: https://svelte.dev/e/effect_update_depth_exceeded` on route `/dictionary`, from Android (Pixel, shell WebView) at 2026-11-19T03:28:52Z and from the desktop web at 03:19:02Z. SvelteKit renders its error page (500).
- Attempt 1 (android) proved the Android addDeckItems local route doesn't 500 (10 scenarios) — don't touch android.
- Same error also reported on `/settings` (count 12, twice), `/stats`, `/notes`, `/` over 11-17..11-19 — likely the same pattern (a `$effect` that writes state it reads, e.g. deck membership / deck list state in web/src/lib/state/dictionary.svelte.ts, web/src/lib/ui/AddToDeck.svelte, web/src/lib/ui/deckPicker.ts, web/src/lib/state/decks.svelte.ts).
- Stack is minified (#m recursion in a shared chunk); build with sourcemaps locally to resolve if needed.

## Do
1. Reproduce in vitest/jsdom (TEST-1 set up bounded-effect tests, see web/tests) or a dev build: open /dictionary entry, toggle deck membership via AddToDeck → effect loop. Fix the cycle (untrack / derive instead of effect-write / compare-before-set).
2. Audit the other reported routes (/settings, /stats, /notes, /) for the same pattern and fix what reproduces; list anything you couldn't reproduce in HANDOFF.
2b. Also: "View note" from a dictionary entry → same loop on /notes; /decks too (03:30Z reports). One shared chunk — likely one shared component.
2c. UX (user): "Add to deck" opens a dropdown/popover of decks to pick (toggle per deck); screenshot 360x800 of it open.
3. Make SvelteKit's client error page for a non-HTTP exception not say "500" (show a friendly "Something went wrong" + reload), i18n.
## Acceptance
- [ ] Bounded-effect test that fails on current code for the add-to-deck toggle on /dictionary; passes after fix.
- [ ] Same for each other route you reproduce.
- [ ] make check MODULE=web green.
