# LF-W4 — Web: per-action "needs connection" for remote-only actions in shell mode

- Phase: P10 local-first · Model: sonnet · Risk: low
- Deps: LF-W3
- Branch: t/LF-W4-web-needs-connection
- Owned paths (only edit these): web/src/lib/ui/access.ts, web/src/lib/ui/ (new small NeedsConnection helper/component), the web route/component files that trigger the actions listed below (only the trigger + label), web/src/routes/messages.ts, tests under web/tests/shell/
- Module(s) for CI: web

## Read
- Contracts: contracts/shell/SHELL.md 9.4, 10.18; contracts/api/shell/ops.json (local vs proxy vs hidden — ops.json wins over the design doc)
- plan/handoffs/LF-W3.md (on t/LF-int)

## Do
LF-W3 found no route is wholly remote, so the route gate (`ROUTE_OPS`) is empty. Remote-only (proxy) actions inside mixed routes need a per-action affordance instead: in shell mode while `shell.connectivity` is offline, the trigger is disabled and shows the existing "needs connection" label; online it works as before. Actions (from LF-W3):
- `createDeck` type=list (/decks/new)
- `putParseOverride` / `deleteParseOverride` (reader, /media/[id]/read)
- `testGrammarRules` (grammar capture, reader)
- `listJpdbVocab` (/import/mapping)
- `getJob` (import/media job polling: show "waiting for a connection" instead of an error while offline)
- `putBlob` uploads (/import, /media/new): SHELL.md 9.4 says not offered in shell → hide the upload entry points in shell mode.
Drive it from ops.json kinds (one helper `needsConnection(opId)`), not a hand-kept list, where practical.

## Acceptance (each bullet → at least one named test)
- [ ] Test: in shell mode offline, a proxy-op trigger is disabled with the label; online or outside shell it is enabled.
- [ ] Test: upload entry points are hidden in shell mode.
- [ ] `make check MODULE=web` green.

## Out of scope
Making any of these actions local.

## Notes from previous attempts
