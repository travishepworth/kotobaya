# BUG-12 — "Open in dictionary" from the Struggle view does nothing

- Phase: P10 (0.1.10) · Model: sonnet · Risk: low
- Deps: none
- Branch: t/BUG-12-struggle-open-dict
- Owned paths (only edit these): web/src/** (struggle/leech view, dictionary/word route, their state/ files), android/app/src/main/** (struggle screen + nav to dictionary/word) only if the same action exists and is broken there
- Module(s) for CI: web (android if touched)

## Do
User report on live 0.1.9: in the Struggle (struggling / leech cards) view, "Open in dictionary" doesn't work. Platform not specified — check web first, then Android.
1. Diagnose: find the action's handler/link; compare with a working "open in dictionary" elsewhere (e.g. Search or review). Likely: wrong route or param (entry id vs note id vs card id), missing entry id in the struggle view's data, no click handler, or link inside a click-intercepting row. State the root cause in HANDOFF with file:line.
2. Fix it on every platform where it's broken. If the struggle API response lacks the entry id the dictionary route needs, use what the view already has (e.g. note's dictionary entry id, or a dictionary search by headword) — do NOT edit contracts/; if impossible, return CONTRACT_CHANGE_REQUEST.
3. Run the web dev UI (make dev-ui / demo fixtures) or a vitest to confirm the action navigates to the right dictionary entry.

## Acceptance
- [ ] Clicking "Open in dictionary" on a struggle row opens that word's dictionary page (web; and Android if it has the action).
- [ ] A small vitest (or state-layer test) asserting the link target for a struggle item — cheap regression guard.
- [ ] make check MODULE=web green (android too if touched; Gradle under `flock /tmp/kotobaya-gradle.lock`).

## Out of scope
Other struggle-view changes; furigana sizing (UI-RUBY, running in parallel on CSS — avoid editing card CSS).
