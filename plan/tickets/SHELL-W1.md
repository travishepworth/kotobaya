# SHELL-W1 — SPA transport, shell state and capabilities, session in shell mode

- Phase: SHELL · Model: opus · Risk: med
- Deps: SHELL-C1
- Branch: t/SHELL-W1-web-transport
- Owned paths (only edit these): web/src/lib/api/client.ts, web/src/lib/api/shell/** (new), web/src/lib/events.ts, web/src/lib/state/session.svelte.ts, web/src/lib/state/shell.svelte.ts (new), web/src/lib/errors/**, web/src/lib/i18n.ts + messages (new keys), web/tests/shell/**, web/mock/** (shell mock)
- Module(s) for CI: web
- Gradle-heavy: no · First phone test: yes

## Read
- Spec: Android app; Web app; Sync and offline
- Design: docs/ANDROID-SHELL.md (ADR-036) — sections 1, 3
- Contracts: contracts/api/shell/SHELL.md, bridge.schema.json, shell.yaml, ops.json
- Decisions: ADR-002, 036

## Do
- `shellFetch(input, init): Promise<Response>`: encodes a `req`, resolves on the matching `res` with a real `Response` (status, headers, body),
  rejects with `TypeError` after 30 s or on `AbortSignal`; non-string bodies → rejected before sending. Both `Configuration`s get
  `fetchApi: shellFetch` and no CSRF header / credentials when `window.KotobayaShell` exists; nothing changes otherwise (browser path byte-identical).
- `events.ts`: in shell mode subscribe to `event` messages with the same `EventStreamOptions` contract (`hello` → `onReconnect`).
- `shell.svelte.ts`: `shell.present`, `shell.info` (from `getShellInfo` via the generated shell client), `shell.online`, `has(capability)`, `modeOf(operationId)` from ops.json; sends `ready`.
- Session: unchanged logic; a local `401` leads to `/login` as today. `logout()` is not offered in shell mode.
- Errors: `shell_offline` / `shell_dict_missing` → one calm toast (`error.offline`), not reported to `/client-errors`; `shell_rebuilding` → retry once after `Retry-After`.
- Dev: `KOTOBAYA_MOCK` gains a fake `window.KotobayaShell` so shell mode runs in a desktop browser.

## Acceptance (each bullet → at least one named test)
- [ ] `shellFetch.test.ts`: round-trip of status/headers/body; concurrent requests answered out of order resolve correctly; timeout and abort reject; duplicate/unknown `res` ignored; every contract bridge example parses
- [ ] `client.test.ts`: without `KotobayaShell` the configuration equals today's (basePath, credentials, CSRF header)
- [ ] `events.shell.test.ts`: hello triggers onReconnect; filtered types respected
- [ ] `make check MODULE=web` green

## Out of scope
Any screen change (W2-W4).

## Notes from previous attempts
