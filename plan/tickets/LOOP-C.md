# LOOP-C — Contracts: review loop (ADR-037) + shell display prefs

- Phase: P10 · Model: fable (architect) · Risk: high (every client builds on it)
- Deps: none (SHELL-A6 may be in flight; no owned-path overlap)
- Branch: t/LOOP-C-contracts
- Owned paths (only edit these): contracts/**, core/src/commonMain/kotlin/kotobaya/contracts/queue/**, clients/** (regenerated only), docs/DECISIONS.md (ADR-037 line), docs/SPEC.md (Queue building note)
- Module(s) for CI: contracts, core (compile only)
- Gradle-heavy: yes (core compile; wrap in `flock /tmp/kotobaya-gradle.lock`)

## Read
- Design: docs/REVIEW-LOOP.md (normative for this ticket; sections 1, 2, 4, 5, 6, 9)
- Spec: Scheduling › Queue building
- Contracts: contracts/api/openapi.yaml (`getReviewQueue`, `ReviewQueue`, `StatsSummary`), contracts/api/shell/{shell.yaml,SHELL.md,ops.json,parity/*}, contracts/core/README.md (C-26, C-27), contracts/db/SCHEMA.md §6, core contract `QueueBuilder.kt`
- Decisions: ADR-031, 032, 033, 034, 035, 036, 037

## Do
1. Core contract Kotlin (interfaces and KDoc only, no implementation): add `LoopPhase`, `LegacyCounts`, `LoopState`, `LoopResult`, `ReviewLoop` exactly as design §2 (file `contracts/queue/ReviewLoop.kt`). Every member gets definition, constraints, example. Put the phase rule (steps 1-4) and the §2 edge cases in the `ReviewLoop` KDoc with the A1/B3/B4/A3 worked examples of §10.
2. `QueueBuilder.kt` KDoc: rewrite rule 4 budget (day remaining + batch, atomic against the day), rule 5 (learning, reviews, new; interleave removed), rule 6 Scope (per client, persisted for the study day), deprecate `newPerSession`, `newShownThisSession`, `moreAvailable` (still computed). Keep every existing signature source-compatible.
3. openapi: add schema `ReviewLoop` and required-on-new-servers property `ReviewQueue.loop` (not in `required`, "absent = server older than ADR-037"), with example; rewrite the `getReviewQueue` description end states into phases and client rules C1-C5; mark `counts`, `more_available`, `next_learning_due_at`, `StatsSummary.due`, `StatsSummary.more_available` `deprecated: true` with the frozen meanings of design §5. Update examples under contracts/api/examples.
4. SCHEMA.md §6: `scheduler.new_per_session` deprecated/ignored. contracts/core README: C-26 device section and C-27 window formula (`B = max(1, keepGoingNewBatch)`).
5. Shell: SHELL.md 4.3 queue note, 7.2 (`LOCAL_API_VERSION` 2 + the added bump reason), 8.4 table (retire `queue.session_cap_more_available`; add the five `queue.loop_*` tags), new parity scenario files of design §9 (inputs + `expect` on `loop`), delete `parity/queue-session-cap-more-available.json`; `check_shell.py` tag list.
6. Shell display prefs: `getDisplayPrefs` / `updateDisplayPrefs`, `ShellDisplay`, `ShellDisplayPatch`, capability `display`, SHELL.md 6.3 + §12 row, examples (design §6).
7. Regenerate clients (`make check MODULE=contracts`), update contracts SUMMARY.md files (decisions, rejected alternatives, the 3 second-look choices of design §12), append the ADR-037 line (scratchpad `loop-adr-037.md`) to docs/DECISIONS.md.

## Acceptance (each bullet → at least one named test)
- [ ] `make check MODULE=contracts` green, no client drift; `check_shell.py` enforces the new tags.
- [ ] `check_kotlin.py` / core compiles with the new contract file (no implementation yet, nothing references it).
- [ ] Every `ReviewLoop` field has description, constraints and example; the openapi example validates against the schema and matches design §5.
- [ ] Each new parity scenario has `expect` assertions on `loop.phase` and the counts named in design §10 (A1, B3, B4, A3, B6).

## Out of scope
DefaultQueueBuilder / DefaultReviewLoop (LOOP-CORE); recording goldens (LOOP-SRV); any server, android, web, cli code.

## Notes from previous attempts
