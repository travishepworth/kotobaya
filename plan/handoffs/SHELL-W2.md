# HANDOFF SHELL-W2

STATUS: DONE
BRANCH: t/SHELL-W2-device-ui
CHECK: `make check MODULE=web` → green (core/build/npm/core symlinked from the main checkout)

## Changed
- Pair screen (login/PairScreen.svelte, `?pair=` prefill, re-pair on auth_required); SyncChip in shell bar; NeedsConnection via layout; canLogout hides Log out.
- Settings "This device" (DeviceSection, SyncSection): sync, dictionary scope+sizes+gap-fill, storage, notifications, push, AnkiConnect, Takoboto, Unpair.
- lib/ui/access.ts (scope + route gating), syncView.ts, shellMessages.ts, lib/state/device.svelte.ts (new, instead of settings.svelte.ts).
- Nav, Devices/Tokens sections, optimizer button hidden by scope (web too). web/Dockerfile copies clients/typescript-shell + ops.json.

## Acceptance → tests
- nav scopes → tests/shell/access.test.ts "scope rule"
- gating from ops.json → access.test.ts "route gating table"
- layout 360x800 / 412x915 → playwright/shell-device.spec.ts (12 pass); screenshots in web/screenshots/shell/ (gitignored, viewed)

## New dependencies
none

## For the reviewer
- /note-types, /struggle, /decks are NOT gated: they contain local ops (listNoteTypes etc.), per the "any local op never gated" rule; ticket text listed note-types.
- Home.svelte (not owned) untouched; stats panels there already show inline errors. Chip is in the shell bar, visible on Home.
- docker build not run (needs Gradle core stage); COPY paths checked against web imports.
