# CORE-28d — Implement C-28f (ADR-046)

- Model: opus (writer-senior) · Risk: high · Deps: C-28f
- Branch: t/CORE-28d (from t/C-28f-contracts; merges together with C-28f) · Owned: per C-28f HANDOFF (core/** except contracts, server/**, android/**, worker/**, fixtures/shell-parity/golden/**)
- CI: core, server, android (+ worker if touched)
## Do
Implement C-28f: DefaultKanjiRequirement (tagged → jōyō only) + tests; the kana-word rule wherever C-28f put it; orphan auto_kanji cleanup if specified. Re-record goldens for changed scenarios. Add a test with the シンガポール entry shape (all forms ateji+rK, uk, 嘉 grade 9 rank 1553): no kanji required, displays シンガポール.
## Acceptance
- [ ] make check core, server, android (and worker if touched) green.

## From C-28f HANDOFF (plan/handoffs/C-28f.md)
- Start: `git checkout -b t/CORE-28d t/C-28f-contracts` (contract commit dca49763).
- core: `DefaultKanjiRequirement` tagged → `KanjiRequirement.isJoyo(grade)` only; re-record `KanjiRequirementTest` / FuriganaFrontTest.
- worker: dictionary build sets `dict_entry.headword` = first reading when every kanji form is `rK`/`sK` (SCHEMA.md 4.5 "Headword", DICT_PACKAGE 2.3); forms/entry_kanji unchanged; force a new dict release (version bump). Worker tests: シンガポール/かるた shapes + an oK-mixed entry stays kanji.
- server/android/web fixture-dictionary loaders apply the same headword rule if they build dict rows from fixtures; record the new parity golden `queue-kanji-rare-forms` (server goldens under fixtures/shell-parity/golden/**).
- Unrequired auto_kanji notes stay (no auto-delete) — do not implement cleanup.
- Owned: core/** except core/src/commonMain/kotlin/kotobaya/contracts/**, worker/**, server/**, android/**, web/** (fixture loaders only), fixtures/shell-parity/golden/**.
- CI: core, worker, server, android (Gradle flock), web if touched.
