# SYNC-NEWSTATE — Phone shows "New" on cards reviewed yesterday via web/CLI (possible sync divergence)

- Phase: P10 · Model: opus (architect role) · Risk: high (sync)
- Owned paths: investigation first (read-only); fixes in server/, android/, core/ as found (report before contract changes)
- Module(s): per fix

## Evidence (2026-11-16)
- User reviewed yesterday (2026-11-15 ~05:27–05:45Z and ~10:27Z) in keep-going mode, via web/CLI (review rows have device_id ...0001 — check what that id is: server/API answers? import?).
- Server now: those cards are state=review, reps 3, due today (1-day graduation) and /review/queue gives is_new=false (verified live, e.g. cards 01a1d760-840f-7a51-8135-a488a132b631, 01a1debb-343e-71e1-b025-509990f484c7, 01a1debb-47a6-794a-948b-e3662f22664b).
- User sees the "New" tag on them today. Web shows New only from is_new; CLI has no New marker; Android shows New only when the LOCAL card row's state == new (ReviewScreen.kt:361, LocalQueueLoader.kt:184). So the phone's local DB very likely still has these cards as `new` — i.e. the server-side reviews never reached/applied on the device. User just installed APK 0.1.25 then 0.1.26 (from 0.1.24).
- Phone (token "Pixel 9 Pro", device 01a1d4c1-0163-7e18-8186-6af3d85059a5, Private Space user 10) did ~33 GET /sync/pull and 2 POST /sync/push in 14 h on 2026-11-15/16.

## Do
1. Establish whether reviews answered via POST /review/answer (web/CLI) emit sync ops that devices pull (review.logged + card state change), and whether the device applies them to the card row (state/due/reps). Check the server op log for the three cards above (read-only prod: kubectl -n kotobaya exec kotobaya-postgres-0 -- psql ...; if kubectl auth fails, say so and continue with code + tests) and the phone's sync cursor vs those ops' positions.
2. Check other plausible causes: device cursor reset/skip on app upgrade, pull pagination dropping ops, op apply failing silently (logged and skipped), HLC/ordering making the device ignore server ops, keep-going answers taking a different server path that doesn't emit ops.
3. Fix the root cause with a test reproducing it (CLAUDE.md: sync/replay worked examples + idempotency/out-of-order). Also say how the user's phone heals (next pull? needs resync?) — never instruct anything that wipes unsynced device reviews.
4. If the device DB is actually fine (state review) and the cause is elsewhere, find where the "New" tag comes from.

## Acceptance
- [ ] Root cause stated with evidence. Failing-then-passing test. make check for touched modules green.
