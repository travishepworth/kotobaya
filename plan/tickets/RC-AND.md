# RC-AND — Review cycle hardening: Android

- Phase: P10 · Model: opus (writer-senior) · Risk: high
- Deps: none
- Branch: t/RC-AND
- Owned paths: android/
- Module(s) for CI: android

## Read
- docs/DECISIONS.md ADR-033, ADR-031; contracts/sync/SYNC.md

## Do (audit findings, RC-AUDIT 2026-11-16)
1. [major] review/ReviewSession.kt:494-498 + ReviewViewModel.kt:317-324 — answering the last in-memory card:
   if rebuild("end of queue") / showFromMemoryLocked inside settleLocked throws (non-ReviewCommitFailed),
   onFailure only sets inline error while state stays Showing → dead card, no taps work, no Retry. Slow
   rebuild also shows the frozen card with no spinner. Fix: wrap settleLocked like answer()'s first show →
   ReviewState.Failed with Retry; show loading during rebuild.
2. [major] ReviewViewModel.kt:223-224 — idle() excludes Summary, so resume / sync dueChanged / day-roll timer
   never reload a Summary screen → "session over" at 09:00 while Today shows 120 due. Treat Summary as idle.
3. [minor→fix] ReviewSession.kt:360 — after sync, markStale only applies at the next answer's settle; the next
   card can come from the stale in-memory queue (already answered on another device) → duplicate review.
   Re-check / rebuild before showing the next card when stale.
4. [minor] queue/LocalQueueLoader.kt:54/137 — counts.new includes toMaterialize but if materialize fails the
   session gets no items → "done while counted". Surface the failure (error + retry), don't show Summary.

## Acceptance
- [ ] Robolectric/unit test: rebuild throws after last answer → Failed state with retry.
- [ ] Test: Summary + dueChanged/resume/day roll → reload shows cards.
- [ ] Test: stale after sync → already-answered card not shown.
- [ ] make check MODULE=android green (Gradle: wrap in flock /tmp/kotobaya-gradle.lock).
