# PORTRAIT-WEB — This device: "Lock to portrait" row

- Phase: SHELL · Model: haiku · Risk: low
- Deps: LOOP-C (generated shell client); PORTRAIT-AND for the phone check
- Branch: t/PORTRAIT-WEB-lock-portrait
- Owned paths (only edit these): web/src/routes/settings/DeviceSection.svelte, web/src/lib/state/device.svelte.ts, web/src/lib/ui/shellMessages.ts
- Module(s) for CI: web
- Gradle-heavy: no

## Read
- Contracts: contracts/api/shell/shell.yaml (`getDisplayPrefs`, `updateDisplayPrefs`), SHELL.md 6.3, 9.4
- Design: docs/REVIEW-LOOP.md §6

## Do
- `device.svelte.ts`: load `getDisplayPrefs` when `ShellInfo.capabilities` includes `display`; `setLockPortrait(v)` calls `updateDisplayPrefs` and reverts the toggle on failure.
- `DeviceSection.svelte`: a toggle row "Lock to portrait" with helper text "Keep the app upright when the phone is turned" (i18n via `shellMessages.ts`), hidden without the capability and outside shell mode.

## Acceptance (each bullet → at least one named test)
- [ ] Row hidden without the `display` capability; toggling calls `updateDisplayPrefs({lock_portrait})` (one vitest on the state class is enough)
- [ ] Screenshot of the This device section at the shell-mobile viewport (no overlap)
- [ ] `make check MODULE=web` green

## Out of scope
Android host behaviour (PORTRAIT-AND); any synced setting.

## Notes from previous attempts
