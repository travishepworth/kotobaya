# SYNC-T1 — Chunked pull: failure-path tests + hardening (BUG-8 review follow-up)

- Phase: P7 · Model: opus (writer-senior) · Risk: med
- Deps: BUG-8
- Branch: t/SYNC-T1-chunked-pull-tests
- Owned paths: core/src/commonMain/kotlin/kotobaya/sync/client/**, core/src/commonTest/kotlin/kotobaya/sync/client/**
- Module(s) for CI: core (flock /tmp/kotobaya-gradle.lock)

## Read
- plan/handoffs/BUG-8.md; contracts/sync/SYNC.md 6.1, 7.8, 9.3, 11

## Do (from reviewer + architect minors on BUG-8)
1. SyncEngineTest with pullChunkOps = 2: unsupported op in chunk 2 → pull_seq just before it, chunk 1 committed; applier throw in chunk 2 → pull_seq at chunk 1's last seq, next pullAll yields the same rows + pull_seq as a one-chunk pull; a no-op-only chunk. Run ClientConvergenceFuzzTest with a random chunk size 1..5.
2. Hardening: check log identity per chunk (or assert bk.pullSeq == previous chunk cursor → Failed(PROTOCOL)); record server skew only on the first chunk of a page; DeviceOpApplier.open @Volatile with a comment on why single-writer makes it safe.
3. Fix DeviceOpApplier.batch KDoc on word_status.updated_at (differs whenever a later op in the chunk touches the entry).

## Acceptance
- [ ] The tests in item 1 (named) pass; make check MODULE=core green
