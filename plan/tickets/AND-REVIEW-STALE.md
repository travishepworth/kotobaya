# AND-REVIEW-STALE — Review tab stays "caught up" after resume or sync

- Phase: P9 hotfix · Model: sonnet · Risk: med
- Deps: AND-QUEUE (merged)
- Branch: t/AND-REVIEW-STALE-refresh
- Owned paths (only edit these): android/
- Module(s) for CI: android

## Read
- android/src/main/kotlin/kotobaya/android/review/ReviewViewModel.kt (~line 142, start/enter/leave)
- plan/handoffs/AND-QUEUE.md

## Do
The reviewer of AND-QUEUE found that the Review tab's session restarts only when its composable is disposed (leave() then enter()). If the app goes to the background while Review shows ReviewState.Empty, resuming shows the stale Empty, and a sync completing doesn't refresh it either. The user reported Review showing nothing due while 299 cards were due.
Fix it: while the session is Empty (and only then; never interrupt an in-progress card), re-run start() when the app/screen resumes and when a sync completes, and when the study day rolls over. Reuse the existing sync-completed signal and lifecycle hooks; add no new dependencies. Keep it coalesced (LoadGate) so there are no load storms.

## Acceptance
- [ ] Test: a session that is Empty becomes non-empty after a sync-completed signal once due cards exist (ViewModel-level JVM test).
- [ ] Test: an in-progress session (card showing) is NOT restarted by a sync-completed signal.
- [ ] make check MODULE=android green.

## Out of scope
Home hero (fixed in AND-QUEUE), server, timezone setting.

## Notes from previous attempts
