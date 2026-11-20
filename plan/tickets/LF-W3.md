# LF-W3 — Web: Setting-up screen for first sync, needs-connection labels

- Phase: P10 local-first · Model: haiku · Risk: low
- Deps: LF-A7, LF-W2
- Branch: t/LF-W3-web-first-sync
- Owned paths (only edit these): web/src/routes/+layout.svelte, web/src/lib/state/shell.svelte.ts, new web/src/lib/ui/FirstSync.svelte, web/src/routes/messages.ts
- Module(s) for CI: web

## Read
- Spec: Clients → Android app
- Contracts: shell.yaml `ShellSyncStatus.first_sync`; SHELL.md 9.4
- Decisions: ADR-050
- Design: scratchpad `local-first-design.md` (orchestrator: copy to `docs/design/local-first.md` or paste the named section into this ticket).

## Do
In shell mode, while `first_sync.done` is false show a full-screen progress ("Setting up: N of M", indeterminate when `total` is null) driven by `shell.sync` events; afterwards never again. Remote-only actions (design section 3, last column) get the existing "needs connection" affordance when `shell.connectivity` says offline. All strings through i18n.

## Acceptance (each bullet → at least one named test)
- [ ] Shown only in shell mode and only while `first_sync.done` is false; disappears on the event without a reload.
- [ ] `make check MODULE=web` green.

## Out of scope
Any data logic.

## Notes from previous attempts
