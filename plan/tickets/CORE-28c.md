# CORE-28c — Core: ADR-045 (DefaultKanjiRequirement) + server goldens/android parity follow-through

- Model: opus (writer-senior) · Risk: high · Deps: C-28e, KANJI-TEXT-SHARED (merged)
- Branch: t/CORE-28c · Owned: core/** except contracts, server/** (golden re-record only + tests), fixtures/shell-parity/golden/**, android/** (tests only if expectations change)
- CI: core, server, android
## Do
Implement C-28e in DefaultKanjiRequirement (+ KanjiRequirementTest, FuriganaFrontTest). Re-record server goldens for changed scenarios; make check core, server, android green.
