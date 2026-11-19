# CORE-28d — Implement C-28f (ADR-046)

- Model: opus (writer-senior) · Risk: high · Deps: C-28f
- Branch: t/CORE-28d (from t/C-28f-contracts; merges together with C-28f) · Owned: per C-28f HANDOFF (core/** except contracts, server/**, android/**, worker/**, fixtures/shell-parity/golden/**)
- CI: core, server, android (+ worker if touched)
## Do
Implement C-28f: DefaultKanjiRequirement (tagged → jōyō only) + tests; the kana-word rule wherever C-28f put it; orphan auto_kanji cleanup if specified. Re-record goldens for changed scenarios. Add a test with the シンガポール entry shape (all forms ateji+rK, uk, 嘉 grade 9 rank 1553): no kanji required, displays シンガポール.
## Acceptance
- [ ] make check core, server, android (and worker if touched) green.
