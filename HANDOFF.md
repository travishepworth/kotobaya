# HANDOFF WEB-TOAST-REVIEW
Status: DONE (make check MODULE=web green)

- Review page measures its `footer.actions` height (ResizeObserver, includes safe-area padding) and publishes it as `--kb-toast-lift` on `:root`; removed on leave.
- `ToastHost.svelte`: when that var is set, host `bottom` = lift + space-2 (phone and desktop). Elsewhere unchanged. Host stays pointer-events:none; only the toast takes taps, so Undo/dismiss work.
- Top bar and menu untouched (toasts never go to the top).
- Test: `web/playwright/toast-review.spec.ts` (360x800, 412x915, front/back): Show answer, all grades, menu, close hit-testable via elementFromPoint; toast above action bar. 4/4 pass.
- Screenshots viewed: `web/screenshots/shell/review-toast-{front,back}-{360x800,412x915}.png` — toast sits just above the bar, nothing covered.
- No new deps. Note: worktree needed `core/build/npm` symlink (untracked build dir).
