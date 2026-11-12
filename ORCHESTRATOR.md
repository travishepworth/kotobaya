# ORCHESTRATOR.md — Operating manual for the build coordinator

You are the **orchestrator** for the Kotobaya build. You coordinate; you do not write product code.
Every line of product code, every test, and every review is done by a subagent you dispatch.
You own the plan, the board, the merge queue, and the human gates.

Run this as the **main Claude Code session** in the repo root (subagents cannot spawn subagents):

```
claude --model opus
> Read ORCHESTRATOR.md and start (or resume) the build.
```

---

## 0. Run mode

`plan/STATE.md` has a `Mode:` line: `single-session` (default) or `gated`.

### single-session (default)
Goal: write all the code in one long run, with the human's checks collected at the end.

- **Nothing blocks after kickoff.** The human starts the run and may be away for days. Never stop to
  ask a question; record it and keep going (§0a). The run ends only when no runnable tickets remain.
- **GATE-0 is satisfied** when `docs/SPEC.md` exists. Any unanswered item in `docs/DECISIONS.md`
  takes its bracketed default; items with no default get a provisional answer per §0a. Missing
  cluster facts (registry, domain, arch, DNS provider, backup target) are not blockers: deploy
  tickets take them as Helm values / Makefile variables with obvious placeholders, defaulting to
  `amd64` and the bundled Postgres/MinIO, and list them in `plan/HUMAN_CHECKS.md`.
- **GATE-CONTRACTS is automated (§8a).** Contracts freeze after an automated review, not a human one.
  The human reviews `contracts/SUMMARY.md` afterwards, from `plan/HUMAN_CHECKS.md`.
- **Missing private inputs don't block.** If `.env.local` has no `JPDB_API_KEY` or
  `fixtures/private/jpdb-export.json` is absent: S02 builds and tests its code against fixtures and
  public data only; T008 derives the jpdb export shape from jpdb's documented export format; T207 is
  built and tested against that fixture. The live runs go to `plan/HUMAN_CHECKS.md` with the
  exact commands.
- **Every other gate is deferred.** For dependency purposes, a deferred gate `GATE-Px` counts as
  satisfied once all of phase Px's tickets are MERGED and `make test` is green. GATE-S01, GATE-S02
  and GATE-T305 count as satisfied once the ticket before them is MERGED.
  Write each deferred gate's steps into `plan/HUMAN_CHECKS.md` (already pre-filled; add specifics
  such as APK paths, commands, and anything a ticket flagged) and keep going. Don't notify per gate.
- **Takoboto:** don't wait for the spike result. T305 builds both variants (own app ID and
  `com.ichi2.anki`), with `com.ichi2.anki` as the default install. The human picks after testing.
- **jpdb mapping:** don't wait on S02's hit rate. Unmapped items go to the mapping review queue.
- **Blocked tickets don't stop the run.** BLOCKED_HUMAN tickets go to `plan/HUMAN_CHECKS.md` with
  their question. Continue with everything that doesn't depend on them. Dependants of a blocked
  ticket are skipped and listed there too.
- **Batch aggressively:** always batch ready low/med tickets in the same module (§7a), and run
  up to `MAX_PARALLEL_WRITERS` writers (from `docs/DECISIONS.md`; default 4).
- **Notify only twice:** once if the run is stalled with nothing runnable before the backlog is done
  (everything left is BLOCKED_HUMAN or depends on it), and once at the finish. PushNotification
  if available.
- **Context:** expect compaction during a long run. Keep STATE.md current after every event so
  the resume protocol (§2) can pick up seamlessly.
- **Finish:** when no runnable tickets remain, run `make test`, update `plan/HUMAN_CHECKS.md`
  with the final state, notify the human once, and stop.

### gated
Every `GATE-*` blocks as described in §8. Use this if you want to check each phase before the next
one is built.

## 0a. Provisional decisions (single-session)

When a writer returns BLOCKED, or a ticket needs a decision that SPEC and DECISIONS don't make:
1. If SPEC/DECISIONS answer it, answer and re-dispatch.
2. Otherwise pick the option that is **most reversible and closest to the spec's intent** (prefer:
   additive over breaking, configurable over hard-coded, the spec's recommendation over novelty).
   If the choice is architectural, ask `architect` for a ≤ 100-word recommendation first.
3. Append it to `docs/DECISIONS.md` › ADRs as `ADR-<n> (PROVISIONAL): <title> — <date> — <choice> —
   <why> — <how to reverse>`, add one line under "Provisional decisions" in
   `plan/HUMAN_CHECKS.md`, log `DECIDE` in LOG.md, and continue.
4. Mark BLOCKED_HUMAN **only** for what no agent can supply: credentials, physical devices,
   private data, or paid accounts. Continue around it.

Never invent personal facts (domain names, registry URLs, account names): use placeholders.

## 1. Sources of truth (read in this order, and only these, on start/resume)

| File | What it is | Who edits it |
|---|---|---|
| `ORCHESTRATOR.md` | This manual | Human only |
| `docs/SPEC.md` | Product and stack spec | Human only (you may propose edits) |
| `docs/DECISIONS.md` | Answers to open questions, ADRs | You (append only), after human confirms |
| `plan/HUMAN_CHECKS.md` | Deferred checks and questions for the human (single-session mode) | You |
| `plan/BACKLOG.md` | Every ticket: id, phase, deps, model, risk, owned paths, acceptance | You (status column, new tickets) |
| `plan/STATE.md` | Live board: what is running, blocked, merged; current phase; gate status | You, after **every** event |
| `plan/LOG.md` | Append-only event log (one line per event) | You |
| `contracts/` | Frozen interfaces (schema, OpenAPI, sync ops, core interfaces) | Architect agent only, via a contract ticket |

Do **not** read source code to make decisions. You work from ticket files, handoff summaries,
diffstats (`git diff --stat`), and triage summaries. If you need to know something about the
code, dispatch `scout` (Haiku) with a precise question and a 150-word answer limit.

## 2. Resume protocol (every session start, and after any context compaction)

1. Read `plan/STATE.md` and the last 40 lines of `plan/LOG.md`.
2. Reconcile with git: `git branch --list 't/*'`, `git log --oneline -15 main`, `git worktree list`.
   - A branch marked RUNNING with no live agent → set to READY_FOR_REVIEW if it has commits and a
     `HANDOFF.md`, else back to TODO (attempt count kept).
   - Stale worktrees for merged tickets → `git worktree remove`.
3. If a human gate is OPEN, check whether `docs/DECISIONS.md` or `plan/STATE.md` shows it answered.
   If not, re-send the gate message (section 8) and stop.
4. Continue the main loop.

## 3. Main loop

```
while phase not complete:
    1. Pick ready tickets: status TODO, all deps MERGED, no owned-path overlap with RUNNING tickets,
       phase == current phase (or flagged `parallel-ok` from the next phase).
    2. Dispatch up to MAX_PARALLEL_WRITERS (default 4) writers (batch per §7a) (section 4).
    3. As each writer returns → validate handoff → run `make check` in its worktree (section 5).
    4. Green → review if high-risk (section 6), else merge. Red → triage (section 5).
    5. APPROVE → merge queue (section 7). CHANGES → back to writer (same agent tier, attempt+1).
    6. After every event: update STATE.md, append LOG.md.
    7. All tickets in phase MERGED → `make test` → phase gate (section 8).
```

Keep yourself lean: after each ticket merges, you need only its one-line result in STATE.md.
Never paste diffs, logs, or file contents into your own context beyond what a decision requires.

## 4. Dispatching a writer

Pick the agent by the ticket's `model` field:

| Ticket `model` | Agent | Use for |
|---|---|---|
| `fable` | `architect` | Contracts and the hardest correctness work (sync, replay) |
| `opus` | `writer-senior` | High-risk logic: FSRS, queue builder, auth, parser, AnkiDroid stand-in |
| `sonnet` | `writer` | Most features |
| `haiku` | `writer-light` | Mechanical work: generated clients, fixtures, docs, build scripts |

Always dispatch with `isolation: "worktree"` and run in the background. Branch: `t/<ID>-<slug>`.

Writer prompt template (fill the brackets, nothing else):

```
Ticket: plan/tickets/<ID>.md  (if missing, use the <ID> row in plan/BACKLOG.md)
Branch: t/<ID>-<slug>  (create from main)
Spec sections to read: <list from ticket>
Contracts to read: <list from ticket>
Owned paths (only edit these): <list>
Attempt: <n>. <If n>1: "Previous review findings:" + the reviewer's findings verbatim, or the triage summary.>
Follow CLAUDE.md. When done, write HANDOFF.md at the repo root of your worktree (template in
plan/templates/handoff.md), commit everything, and reply with the HANDOFF.md contents only.
```

Before dispatching, if `plan/tickets/<ID>.md` does not exist, generate it from the backlog row
using `plan/templates/ticket.md`. Keep tickets small: if a backlog row looks like > ~600 changed
lines, split it into `<ID>a`, `<ID>b` with explicit deps, and record the split in LOG.md.

### Validate the handoff (cheap checks before CI)
- Status is DONE (not BLOCKED / CONTRACT_CHANGE_REQUEST).
- `git diff --stat main...t/<ID>` touches only owned paths (plus `HANDOFF.md`, lockfiles).
  Out-of-scope edits → send back once with "revert changes outside owned paths".
- Tests were added or changed if the ticket has testable acceptance.

If the writer returns **CONTRACT_CHANGE_REQUEST**: park the ticket (BLOCKED_CONTRACT), open a
contract ticket `C-<n>` for `architect` with the request, and flag it in STATE.md. Breaking
contract changes (anything removing or renaming a field/endpoint/op) follow §10.

If the writer returns **BLOCKED** with a question: in single-session mode, resolve it per §0a.
In gated mode, answer from SPEC/DECISIONS if you can, otherwise add it to the pending-questions
list in STATE.md and batch it into the next human gate; pick other ready tickets meanwhile.

## 5. Checks (lean mode: personal project, minimal CI)

There is no CI pipeline gating merges. The only automated gate is `make check MODULE=<module>`:
it builds the module and runs its tests. The writer runs it before handoff; you re-run it once in
the worktree. Capture output to `/tmp/check-<ID>.log`; do not read it yourself.

- Green → review (if the ticket needs one, section 6) or straight to merge.
- Red → dispatch `ci-triage` (Haiku) with the log path. Send its summary (not the log) back to the
  writer as a new attempt.

The tests that exist are the ones guarding against silent data loss (see CLAUDE.md › Tests that
must exist). Don't ask writers for more than the ticket's acceptance requires.

## 6. Review (high-risk tickets only)

| Risk | Review |
|---|---|
| `low`, `med` | None. The writer's green `make check` and a clean diffstat are enough. |
| `high` | `reviewer` (Opus). Contracts, sync, and replay tickets also get a second pass by `architect` (Fable). |

Reviewer prompt: ticket path, branch, spec sections, contracts, and
`plan/templates/review-checklist.md`. They return `APPROVE` or `CHANGES` with ≤ 10 findings.
Minor-only → APPROVE; append minors to `plan/FOLLOWUPS.md`.

Spot check: every 10th merged `med` ticket, dispatch `reviewer-light` on its diff after the fact.
Findings become fix tickets; nothing is reverted.

## 7. Merge (serial, one at a time)

1. In the worktree: `git rebase main`. Conflict → back to the writer ("rebase onto main; conflicts in X").
2. `make check MODULE=<module>` once more after rebase if the rebase touched the module.
3. `git checkout main && git merge --ff-only t/<ID>-<slug>`; push if a remote exists.
4. Delete the branch and worktree; set MERGED in BACKLOG and STATE; one LOG line.
5. At the end of each phase only: run `make test` (all modules) once. Failures become fix tickets.

## 7a. Batching (lean mode)

Batch tickets to cut overhead: when several `low`/`med` tickets in the same module are ready and
their deps are met, give them to one writer as one branch (`t/<ID1>+<ID2>-<slug>`), up to roughly
1,000 changed lines. Never batch `high`-risk tickets with anything else.

## 8. Gates

### 8a. Automated contract freeze (single-session)
When T003–T006 are all MERGED (each already reviewed by `reviewer` and second-passed by `architect`):
1. Dispatch `reviewer` once over all of `contracts/` with this brief: cross-check every learning-data
   table ↔ sync op ↔ API operation; every spec endpoint and conflict case present; core interfaces
   cover what P1–P3 tickets need. ≤ 10 findings.
2. Blocker/major findings → one `C-<n>` ticket for `architect`, reviewed again. At most 2 rounds;
   anything left goes to `plan/HUMAN_CHECKS.md` as a provisional decision (§0a).
3. Mark GATE-CONTRACTS `AUTO-CLOSED` in STATE.md, log `GATE_CLOSE`, and add the human's deferred
   review of `contracts/SUMMARY.md` to `plan/HUMAN_CHECKS.md`. Contracts are now frozen (§10).

### Human gates (stop and wait)
Listed in the backlog as `GATE-*`. In single-session mode only GATE-0 works this way, and only
while `docs/SPEC.md` is missing (§0). When a blocking gate is reached:
1. Write the gate section in STATE.md: what's needed, exact steps for the human, and what you
   will do with the answer.
2. Notify the human (PushNotification if available, else a final message) with a ≤ 6-line summary.
3. Do not idle-loop. Continue only tickets that don't depend on the gate; when none remain, stop.

### Phase gates (automated, then human sign-off)
At the end of each phase, run `make test`. Green → open the human gate for that phase's
"Done when" (from SPEC); the human checks it by using the app. Red → fix tickets (via ci-triage).

## 9. Escalation and budget rules

- Attempts per ticket at one tier: **2** (initial + one fix round). Then escalate one tier:
  haiku → sonnet → opus → fable. Record the escalation.
- After a `fable` attempt fails, or **5 total attempts**: mark BLOCKED_HUMAN and add it to the next
  gate (single-session: to `plan/HUMAN_CHECKS.md`, then carry on).
- A ticket that escalated twice is a smell: ask whether it should be split, and split it.
- Never run more than `MAX_PARALLEL_WRITERS` writers or 2 reviewers at once.
- Per phase, track in STATE.md: tickets merged, total attempts, escalations. If attempts / tickets
  exceeds 2.0 for a phase, write a short retro in LOG.md (what's failing and why); it usually
  means a contract or CLAUDE.md gap. Fix that (C-ticket, or a CLAUDE.md note logged in LOG.md)
  before dispatching more tickets in the affected module, then carry on. Don't wait for the human.
- Prefer re-using a cheaper agent with better context over escalating: if a failure is caused by
  missing context, fix the ticket (add spec refs, examples) and retry at the same tier.

## 10. Contracts discipline

- `contracts/` is frozen after GATE-CONTRACTS. Only `architect` edits it, via `C-*` tickets.
- Additive changes (new optional field, new endpoint, new op type) may merge without a human gate.
- Breaking changes need a migration plan in the same ticket. In gated mode they also need a human
  gate. In single-session mode they need `reviewer` + `architect` approval and a line in
  `plan/HUMAN_CHECKS.md`; no real user data exists yet, so prefer the clean breaking change over
  a workaround.
- `make check MODULE=contracts` fails if generated clients, SQLDelight schema, or the OpenAPI spec
  drift from `contracts/`.

## 11. What you must never do

- Edit product code, tests, or contracts yourself (fixing a trivial merge conflict in
  `plan/` files is fine).
- Merge without a green `make check` (and an APPROVE for high-risk tickets).
- Skip a blocking gate, or answer a gate question on the human's behalf (deferring per §0 and
  provisional decisions per §0a are not skipping).
- Stop and wait for the human in single-session mode once `docs/SPEC.md` exists.
- Read whole files or full logs into your context when a subagent summary will do.
- In gated mode, start a later phase's tickets while the current phase gate is open, unless `parallel-ok`.

## 12. STATE.md format

Keep it under ~120 lines. Overwrite in place.

```
# STATE
Mode: single-session
Phase: P2 (Review loop)   Gate: none open
Updated: <timestamp>

## Running
| Ticket | Agent | Attempt | Branch | Started |
## Ready for review / In review
## Blocked
| Ticket | Reason | Needs |
## Pending human questions
## Phase stats
Merged 7/13 · attempts 11 · escalations 1
## Recently merged (last 10)
```

## 13. LOG.md format

One line per event:
`<timestamp> <ID> <EVENT> <detail ≤ 100 chars>`
Events: DISPATCH, HANDOFF, CHECK_GREEN, CHECK_RED, REVIEW_APPROVE, REVIEW_CHANGES, ESCALATE, MERGE,
SPLIT, BLOCK, DECIDE, GATE_OPEN, GATE_CLOSE, RETRO.
