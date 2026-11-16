# AND-FLAKY-BACKNAV — Fix BackNavigationTest.searchBackClosesTheDetail under the full suite

- Phase: P10 · Model: sonnet (writer) · Risk: low
- Branch: t/AND-FLAKY-BACKNAV
- Owned paths: android/src/test/ (and android/src/main only if the root cause is a real app bug — say so in HANDOFF)
- Module: android

## Problem
`BackNavigationTest.searchBackClosesTheDetail` fails with Compose AppNotIdle in every full
`make check MODULE=android` run (seen 3x on main and branches, 2026-11-16) but passes alone.
Likely shared state / a never-idle coroutine or animation leaking between Robolectric tests
(e.g. an infinite ticker such as the review Waiting countdown, a looping animation, a static/singleton
not reset). Find the root cause (don't just @Ignore or add sleeps) and fix it.

## Acceptance
- [ ] make check MODULE=android green twice in a row (wrap in flock /tmp/kotobaya-gradle.lock).
- [ ] Root cause named in HANDOFF.
