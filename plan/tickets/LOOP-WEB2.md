# LOOP-WEB2 — Loop UI follow-ups

- Model: haiku (writer-light) · Risk: low · Deps: LOOP-WEB · Gradle: no
- Owned: web/src/routes/settings/** (StudySection), web/src/lib/settings/** or settings keys, web/src/lib/ui/Loop*.svelte, web/src/routes/review/LoopEnd.svelte, web/mock/**, web/tests/**, web/playwright/**
## Do
1. Done-for-today card (Home + review end screen): show the explicit quotas the user confirmed — "200/200 reviews · 40/40 new" (reviews line only in capped mode) — plus the backlog line and Keep going. goal_reached: "40/40 new · New-card goal reached".
2. Remove the Settings "New cards per session" row (scheduler.new_per_session is ignored per ADR-037); keep the key readable, just don't offer it.
3. web/mock/fake-shell.js: report local_api_version 2 so shell-mode specs don't show "update".
## Acceptance
- [ ] Screenshots 360x800 of both end screens viewed; make check MODULE=web green.
