# LOOP-34 — Review total counts only cards doable right now (ADR-054)

- Phase: P11 · Model: opus (writer-senior) · Risk: high
- Deps: C-34 (branch from t/C-34-until-stop-ready-now)
- Branch: t/LOOP-34-until-stop-ready-now
- Owned paths (only edit these): core/src/commonMain/kotlin/kotobaya/queue/ (DefaultReviewLoop.kt, DefaultLoopPresenter.kt), core/src/commonTest/, core/src/jsMain/kotlin/kotobaya/queue/, server/src/test/, android/src/test/, fixtures/shell-parity/golden/, acceptance/blackhole/src/test/kotlin/kotobaya/acceptance/blackhole/LoopInvariants.kt, web/src/routes/review/+page.svelte, web/src/lib/review/, web/src/lib/core/index.ts, web/src/lib/state/review.svelte.ts, cli/src/kotobaya_cli/watch.py, cli/tests/
- Module(s) for CI: core, server, android, web, cli (whichever you touch; all must be green)

## Read
- Contracts (as changed by C-34): openapi.yaml `ReviewLoop` C3/C4; core contracts/queue/ReviewLoop.kt, LoopPresenter.kt
- Decisions: ADR-051, **ADR-054**

## Bug (user report, 2026-11-22)
Home: 4 to review. Review header: "of 15". Live state: ready 2, later_today 13, until_stop_parts {learning 2, later 13, review 0, new 0}. Header total = done + until_stop, and until_stop sums `later` (learning cards on a timer), so the user reaches the waiting/stop screen with the header still showing cards. Same root cause as the earlier "count disagrees" reports: fix it once, in core, with a test that keeps it fixed.

## Do
1. core `DefaultReviewLoop`: `untilStop = parts.learning + parts.review + parts.new`.
2. core `DefaultLoopPresenter`: `untilStop = learning + review + new` in both branches (rule 3 and projection).
3. Tests (core, kotlin.test):
   - The reported case as an example test: ready 2 learning + 13 later + goal met ⇒ untilStop 2.
   - Extend the ADR-051 property/fuzz test: for random queue states + answer sequences, `untilStop > 0` ⟺ the phase serves a card now (REVIEWING with a servable item); `untilStop == 0` ⟹ phase is WAITING/GOAL_REACHED/DONE_FOR_TODAY/EMPTY; and through the presenter with pending answers, the header total never stays > 0 when the next applied state is terminal or waiting.
4. Re-record goldens / update assertions that encode the old sum (server ReviewApiTest, KanjiFirstTest; android ParityExpectTest, QueueKanjiMintTest; fixtures/shell-parity/golden; acceptance LoopInvariants). Do not change any other expected value.
5. Web review header (`+page.svelte`): remove the `later` chip; the later-today hint stays where it is. Home's big number stays `ready.total`. Check that `web/src/lib/core/index.ts` passes `untilStop` through unchanged.
6. CLI `watch.py`: if any total/bar sums `later`, stop doing so. Note: cli/ has uncommitted unrelated work on main; touch only what this needs.

## Acceptance (each bullet → a named test)
- [ ] Reported case: untilStop == 2 (core example test).
- [ ] Fuzz: untilStop > 0 ⟺ a card is servable now, across random states and answer sequences.
- [ ] Presenter: a pending answer that returns today leaves untilStop (moves to later).
- [ ] `make check` green for every module touched.

## Out of scope
Contract text (C-34). Queue order, phases, keep going rules: unchanged.

## Notes from previous attempts
