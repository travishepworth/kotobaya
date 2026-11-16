# RC-FOLLOW-AND — RC-AND + REVIEW-UNLIMITED review minors (Android)

- Phase: P10 · Model: sonnet (writer) · Risk: med
- Deps: AND-FLAKY-BACKNAV, REVIEW-UNLIMITED merged
- Branch: t/RC-FOLLOW-AND · Owned paths: android/ · Module: android

## Do (plan/FOLLOWUPS.md)
1. ReviewViewModel.kt:229-243 — reload of Summary into an equal state doesn't re-arm scheduleDayRoll; re-arm at the end of refreshIfEmpty. Test: two day rolls with screen left on.
2. ReviewViewModel.kt:147-157 — refreshIfEmpty start() throwing shows an extra inline error over the Failed page; treat as load failure.
3. ReviewSession.kt:443-447 — every due-changing sync forces a full rebuild + Loading (WebView teardown) on the next answer; instead check whether the next in-memory card is still due/unanswered and only rebuild if not.
4. Test that LocalQueueLoader passes reviewsUnlimited into QueueLimits (3 due, cap 1, key true → 3).
5. SettingsViewModels.kt:51 — an invalid number field blocks saving the reviews-unlimited toggle; save the toggle independently.

## Acceptance
- [ ] Tests for 1, 3, 4. make check MODULE=android green (flock /tmp/kotobaya-gradle.lock).
