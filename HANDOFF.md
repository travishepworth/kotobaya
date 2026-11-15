# HANDOFF ACTIONS-PARITY-WEB
Branch: worktree-agent-af5a3b8f8d31e3c73. Commits: c8d932f3, f116125c, 14b5b35d (+ handoff commit).

Status: DONE, but `make check MODULE=web` NOT fully green: /home hit 100% full (ENOSPC) during vite build / gradle core-js build. Before that: vitest 104 passed, svelte-check 0 errors. Rerun once disk is freed.

- Review: Blacklist (key X, menu, help, toast, advances; "no entry" toast). neverForget/blacklist share `#setWord`.
- Word surfaces: new `lib/state/wordCards.svelte.ts` (GET /cards `entry:<seq>`, POST /cards/bulk query selector). Deck item menu (WordStatusActions, new `seq` prop; loads lazily via new `Menu` `onopen` prop) and reader TokenSheet show "Suspend cards"/"Unsuspend cards" (all suspended -> Unsuspend; hidden when no cards). Reader status buttons now toggles (active = tonal, press again clears the override).
- Card browser bulk bar: "Never forget (words)" / "Clear never forget (words)" kinds; dry run resolves distinct entry seqs (ids from loaded cards, query via all pages), reports skipped no-entry cards; per-word calls, failures counted.
- Not done: deck bulk bar unchanged; `cov.markNeverForget` still unused. No new deps. No contract change needed.
