# HANDOFF ACTIONS-PARITY-AND — DONE
Branch worktree-agent-a693a89d477f5857d.
- Suspend (S), never-forget (N), bury (B) keys on review; deck word sheet marks active status and has suspend/unsuspend-cards toggle; word page "My cards" has never-forget toggle, suspend-all, per-card suspend. All via sync ops (card.suspended/unsuspended, one per live card, idempotent).
- Test: WordCardSuspensionTest.
- make check MODULE=android: compiles; 392 tests, 1 failure (BackNavigationTest, Compose idle timeout) which passes when rerun alone — flaky under load, unrelated to this change.
- No new dependencies.
