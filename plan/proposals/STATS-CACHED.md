# PROPOSAL (not a ticket) — Heavy stats and hot lists become `cached` in the shell

> **Status: UNVALIDATED.** The 4.1–4.5 s phone times include waiting on the 1-wide lane, and the handlers took 10–35 ms each on desktop. Becomes a contract ticket only if PERF-GATE shows their own compute time on the phone is large. Known cost: the heatmap and hot lists would leave out today's reviews until they are revalidated.

- Phase: P11 perf · Model: opus (architect) · Risk: med
- Deps: PERF-LF (merged)
- Branch: t/C-33-stats-cached
- Owned paths (only edit these): contracts/api/shell/SHELL.md, contracts/api/shell/ops.json, contracts/api/shell/examples/, contracts/api/shell/parity/ (if the class change needs it), android/src/main/kotlin/kotobaya/android/shell/api/ApiOperations.kt (route classes only), the Android route-table test, clients/ (regenerate only)
- Module(s) for CI: contracts, android, web

## Why
This is the part of "load the numbers from the internet" that survived independent review (plan/handoffs/PERF-REVIEW-2026-11-21.md). getStatsHeatmap, getStatsRetention, listHotKanji and listHotWords are slow on the phone (4.1–4.5 s on the first open). They change slowly, and nothing compares them against the review counts. The class `cached` (C-31) already exists and already covers getStatsForecast, getStatsKnownWords and getStatsCalibration. A hit answers at once from the device's response cache and is revalidated in the background, and it never touches the network on the request path (L-9).

## Do
1. Change those four operations from `local` to `cached` in ops.json and in the SHELL.md class table. Keep the counts in SHELL.md correct and add a changelog line.
   - getStatsSummary, listDecks and getReviewQueue stay `local`. They carry today's counts, which must match the review header (ADR-051(b)).
2. Check the consequences:
   - A miss is `503 shell_uncached` followed by `cache.updated`. The SPA already shows placeholders for this (LF-W2), and PERF-SNAP shows the snapshot copy first.
   - Offline with no stored copy, those widgets show a placeholder.
   Write down in SHELL.md which handlers stay local and why.
3. Update ApiOperations.kt and the route-table test so they match ops.json. If the local handlers for these four operations become unused, leave them in place; removing them is a later cleanup.

## Acceptance (each bullet → at least one named test)
- [ ] `make check MODULE=contracts` green (ops.json and SHELL.md agree, no client drift).
- [ ] The Android route-table test passes with the new classes.
- [ ] Test: the shell answers the four operations from the response cache with no network on the request path. A miss returns 503 shell_uncached followed by cache.updated.
- [ ] `make check MODULE=android` and `make check MODULE=web` green.

## Out of scope
Any change to how today's counts are computed. Server endpoints. A new combined `/home/summary` endpoint (rejected).
