# SHELL-W2 — SPA device UI: pair screen, sync chip, device settings, hide by scope, needs-connection states

- Phase: SHELL · Model: sonnet · Risk: med
- Deps: SHELL-W1
- Branch: t/SHELL-W2-web-device-ui
- Owned paths (only edit these): web/src/routes/login/**, web/src/routes/settings/** (new DeviceSection, SyncSection), web/src/routes/+layout.svelte, web/src/lib/ui/AppShell.svelte, web/src/lib/ui/SyncChip.svelte (new), web/src/lib/ui/NeedsConnection.svelte (new), web/src/lib/state/settings.svelte.ts, messages, web/tests/**
- Module(s) for CI: web
- Gradle-heavy: no · First phone test: no

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 3
- Contracts: contracts/api/shell/shell.yaml, ops.json
- Decisions: ADR-036; memory rule: UI changes need a layout test + screenshot

## Do
- Shell mode `/login` = Pair screen (server URL, code, paste of a `kotobaya://pair` URI, `?pair=` prefill) calling `pairShell`.
- Sync chip on Home and in the shell bar: last sync, pending count, 48 h warning, tap = `syncNow`; fed by `shell.sync` events.
- Settings → "This device" (capability-gated rows): sync, dictionary install with progress, offline storage budget + breakdown,
  notification permission and nudges, UnifiedPush, AnkiConnect, Takoboto status, Unpair (confirm; warns about pending ops).
- Nav and sections hidden when the principal lacks the scope (Import, Tokens, Devices list, optimizer) — applies on the plain web too.
- Routes whose operations are all `proxy` (stats, media, reader, note-types, import mapping) render `NeedsConnection` while `shell.online` is false; Home's stats panels degrade inline.

## Acceptance (each bullet → at least one named test)
- [ ] Vitest: nav for a principal with read/write/review omits Import and Tokens; with all scopes it is unchanged
- [ ] Vitest: route gating table derived from ops.json (a route with any `local` op is never gated)
- [ ] Playwright layout test at 360x800 and 412x915 with the shell mock: Pair, Home with chip, device settings — no overlap/overflow; screenshots written to web/screenshots/shell/

## Out of scope
Native handlers (A6/A7).

## Notes from previous attempts

## Orchestrator note (2026-11-16)
Dictionary row: show the installed scope with a choice of top60k / all (size from getDictRelease artifacts) and the SHELL-L9 gap-fill counts (cached, still missing).
