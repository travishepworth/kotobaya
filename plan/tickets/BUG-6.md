# BUG-6 — jpdb history replay leaves ~1,100 cards stuck in (re)learning

- Phase: P7 (live bugfix) · Model: opus (architect role, ADR-004) · Risk: high (sync/replay + core FSRS → reviewer + architect second pass)
- Deps: none
- Branch: t/BUG-6-import-graduation
- Owned paths: `contracts/sync/` (replay/decide text, scheduler-version note, worked example, model.py), `core/src/commonMain/kotlin/kotobaya/` (replay/decide + scheduler version constant only; NOT the FSRS scheduler's live semantics), `core/src/*Test/`, `acceptance/` (only if the convergence test belongs there), `server/src/test/` (only for a server-side replay test)
- Module(s) for CI: contracts, core, server (and acceptance if touched)

## Read
- Contracts: `contracts/sync/SYNC.md` (replay/decide, 10.5 core_scheduler_version), `contracts/sync/OPS.md` + `ops/review.logged.schema.json` (import_source), `contracts/db/SCHEMA.md` (review_log.import_source)
- Decisions: ADR-008 (jpdb grade mapping — unchanged)

## Live report
After jpdb history import, ~1,100 cards stuck: 1,021 learning + 86 relearning, ~9 reviews each, all overdue.
Grade mapping is correct and stays (ADR-008): nothing/something → Again, hard → Hard (pass), okay → Good,
easy → Easy. But core FsrsScheduler (py-fsrs semantics, hardStepMs) treats Hard on a (re)learning card as
"repeat this step" regardless of elapsed time. jpdb "hard" is the most common grade (35,760 / 78.5k), so a
word failed once and later graded hard never graduates.

## Do
- Import replay only (review_log.import_source = jpdb): a pass (Hard/Good/Easy) on a learning/relearning
  card ≥ 1 day after the previous review graduates the card to Review (FSRS memory state computed as for a
  graduating pass; specify exactly in SYNC.md). Live reviews (no import_source) keep FSRS semantics unchanged.
- Amend the contract (additive semantics change scoped to import reviews), document it in SYNC.md with a
  worked example, update contracts/sync/tools/model.py if it models decide.
- Re-replay goes through the normal path: bump the core scheduler version so SYNC.md 10.5 full replay runs on
  server startup and on each device after the app update. Never edit card state directly. Address the
  window where server and an un-updated Android device run different cores (state in HANDOFF whether caches
  can diverge and why it's safe, or what's needed).
- Propose the ADR text (ADR-021) in HANDOFF; the orchestrator appends it to docs/DECISIONS.md.

## Acceptance
- [ ] All existing FSRS golden vectors still pass unchanged.
- [ ] Test: import history Again, Good, Hard, Hard spread over days → final state Review. The same grades as live (non-import) reviews → unchanged FSRS behaviour.
- [ ] Test: Hard on an import (re)learning card < 1 day after the previous review → FSRS step behaviour unchanged.
- [ ] Replay/convergence test (server-side replay = device replay; existing fuzz extended with import-sourced reviews).
- [ ] Test: scheduler version bump triggers full replay, and stuck cards end in Review.
- [ ] make check for contracts, core, server green.
