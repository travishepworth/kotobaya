# BUG-8 — Android first sync is slow and ANRs: main-thread DB access, single connection, per-op costs (I-4, I-7)

- Phase: P7 · Model: opus (writer-senior) · Risk: high (reviewer + architect second review)
- Deps: HOTFIX-0.1.7
- Branch: t/BUG-8-android-sync-perf
- Owned paths: android/** (except android/**/ui/** visual layout beyond moving DB reads out of composition), core/src/**/sync/ (SyncEngine, DeviceOpApplier, AutoStatus, SyncSchemas/DefaultOpCodec validation) and their tests, acceptance/ only if a bench lives there
- Module(s) for CI: android, core (and acceptance if touched). Use `flock /tmp/kotobaya-gradle.lock make check MODULE=...` for every Gradle build.

## Read
- Evidence: plan/handoffs/INBOX-0.1.6.md › I-4 and I-7 (ANR traces summarised there)
- Spec: sync client sections; Contracts: contracts/sync/SYNC.md (worked examples), contracts/sync schemas
- Do NOT change contracts/. If a snapshot bootstrap for first sync is needed, return CONTRACT_CHANGE_REQUEST.

## Do
Today a fresh device pulls 112k ops at ~10 s per page. Sync holds the only SQLite connection for a whole page
(SyncEngine.pullAll → SqlDelightDeviceStore.transaction, SyncEngine.kt:163) while composables read the DB on the main
thread inside composition (TakobotoFeature.kt:105 `store.read{ learning.all(NOTE_TYPE) }` in TakobotoDebug).
Required:
1. No DB access on the main thread or inside composition anywhere in android/: audit every screen; move to
   Dispatchers.IO and expose as state/Flow. Enable StrictMode (detectDiskReads/Writes, penaltyLog + penaltyDeath in a
   test) in debug.
2. WAL with separate reader connection(s), so reads never wait for the sync writer.
3. Pull pages applied in bounded transactions (chunks) that yield between chunks; card-cache replay deferred to the end
   of the cycle, once per touched card, if it is currently per op.
4. AutoStatus lookup (AutoStatus.noteIdsOf → SqlLearningTables.select, SqlLearningTables.kt:173, called from
   DeviceOpApplier.applyTo:68 per op) indexed or batched.
5. SyncSchemas.validate / DefaultOpCodec.decodeTree (DefaultOpCodec.kt:120) per-op cost cut (no quadratic recursion).
   Must remain correct: the same ops must be accepted/rejected as before (architect signs off).
6. Visible "Syncing N of M" progress (i18n string) on the sync chip/screen.

## Acceptance
- [ ] SYNC.md worked examples and the convergence fuzz stay green (core + acceptance)
- [ ] Synthetic 100k-op pull applies in < 2 min on a JVM/Robolectric bench; report the real number in HANDOFF
- [ ] Validation equivalence test: old vs new validator agree on the existing valid/invalid op examples + fuzzed ops
- [ ] No StrictMode disk-read violation on the main thread (Robolectric test over the main screens)
- [ ] Reads proceed while a sync write transaction is open (test with WAL reader)

## Out of scope
Visual redesign (UI-APP); ViewModel restructuring beyond what removing main-thread IO needs (UI-FOUND does the full
ViewModel pass — keep changes minimal and compatible); server changes.
