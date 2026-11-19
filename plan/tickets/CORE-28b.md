# CORE-28b — Core: apply C-28d; backfillPart perf; ruby escaping test

- Phase: P10 · Model: opus (writer-senior) · Risk: high · Deps: C-28d
- Branch: t/CORE-28b-core · Owned: core/** except core/src/commonMain/kotlin/kotobaya/contracts/** · CI: core
## Do
1. Implement C-28d; QueueRandom.rebuildExcluded narrowed to what the contract excludes (drop the wider filter).
2. Perf: DefaultQueueBuilder.backfillPart (~L580) scans all introducedWords per call → build a literal→MissingKanji map once per Build.
3. FuriganaFrontTest: golden with markup/entities in expression and reading (`<img onerror=…>`, `a&b`), assert escaped <rt>.
## Acceptance
- [ ] Parity mirrors for C-28d steps; probe-style random test with the contract's exclusions passes; make check MODULE=core green.
