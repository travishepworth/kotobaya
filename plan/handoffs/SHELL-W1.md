# HANDOFF SHELL-W1

STATUS: DONE
BRANCH: t/SHELL-W1-spa-transport
CHECK: `make check MODULE=web` → green (used the existing core npm build, no Gradle)

## Changed (≤ 6 lines)
- `web/src/lib/api/shell/`: protocol validators, `ShellTransport`/`shellFetch`, `sendReady`, generated shell client config, `ops.json` lookup
- `api/client.ts`, `errors/index.ts`: in shell mode use `fetchApi: shellFetch` with no credentials and no CSRF header. Browser config unchanged. Shell 503s: offline/dict_missing give one info toast and are not reported; rebuilding is retried once
- `events.ts`: in shell mode, events come from bridge `event` messages. `hello` is replayed to streams opened later. `state/shell.svelte.ts`: present/info/online/incompatible, `has`, `modeOf`, `initShell`
- `session.svelte.ts`: `refreshSession` calls `initShell`; `canLogout`; `logout()` does nothing in shell mode
- `mock/fake-shell.js` and `demo-api.ts`: fake `KotobayaShell`, turned on with `/__mock/shell/on` or `KOTOBAYA_MOCK_SHELL=1`. Smoke-tested in Chromium

## Acceptance → tests
- shellFetch → `tests/shell/shellFetch.test.ts` (round trip, out of order, timeout, abort, duplicate/unknown res, every valid and invalid contract example)
- client → `tests/shell/client.test.ts` "browser mode … configuration equals today"
- events → `tests/shell/events.shell.test.ts`
- extra → `tests/shell/shellState.test.ts`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- **Breaks the release image:** `web/Dockerfile` (not owned) must add `COPY clients/typescript-shell …` and `COPY contracts/api/shell/ops.json …`
- An abort rejects with `AbortError` like `fetch` does, not `TypeError`, so a cancelled request shows no "network error" toast. The layout still shows Log out; it should hide it using `canLogout` (W2)
- `ready.web_version` is `VITE_APP_VERSION` (the manifest uses package.json). `MIN_LOCAL_API` is defined twice (protocol.ts and scripts/)
