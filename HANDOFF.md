# HANDOFF T212

STATUS: BLOCKED (web half done; server half blocked on T202b)
BRANCH: t/T212-web-settings-r
CHECK: `make check MODULE=web` → green. Server not started.

## Changed (≤ 6 lines)
- web/src/routes/settings/ (page + attribution), web/src/lib/settings/: study settings, presets (incl. Easy Days, optimise enqueue), devices (rename/revoke, QR pairing with countdown), PAT create with show-once dialog + revoke, server status, own `messages.ts` (not edited shared i18n.ts, to avoid conflicts).
- Settings keys beyond SCHEMA §6: `scheduler.kanji_before_vocab` (bool, false), `scheduler.mined_priority` (int, 0; ADR-006). Core must read the same names.

## Acceptance → tests
- Server tests (settings keys, preset ops, scope `write`) → not done, blocked.
- Manual items → untested (no running stack).

## New dependencies
- qrcode-generator (web/package.json + lockfile): tiny, no-dep client-side QR SVG; avoids hand-rolling an encoder.

## For the reviewer (≤ 3 lines)
- API gaps for UI: `/meta` has no backup fields (last verified backup, durable_seq, BACKUPS_DISABLED); UI shows a "not reported" note. Scope descriptions say `write` covers bulk scheduling (CCR candidate unresolved).

## If BLOCKED
QUESTION: T202b (OpApply) is not on main. Server half (`kotobaya/settings/`: listSettings, updateSettings, preset CRUD/optimize, tests) needs it. Merge T202b then redispatch the server half, or authorise stubbing?
