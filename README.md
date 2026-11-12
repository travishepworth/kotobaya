# Kotobaya build kit

Drop these files into an empty git repo. One orchestrator session (Opus) coordinates the whole
build; every line of code, test, and review is done by subagents it dispatches.

**Lean mode (default):** no CI pipeline, no lint gates, no e2e or perf tests. The only automated
gate is `make check` per module, and tests exist only where bugs would silently lose data
(FSRS, sync, replay, auth, importers, parser). Only high-risk tickets get reviewed; small tickets
are batched. Phase checks are done by you, using the app.

## What's here
| File | Purpose |
|---|---|
| `ORCHESTRATOR.md` | The orchestrator's operating manual: loop, dispatch, CI, review, merge, gates, escalation |
| `CLAUDE.md` | Rules every agent follows: module map, commands, hard rules, handoff |
| `plan/BACKLOG.md` | All ~75 tickets across 7 phases, with model, risk, deps, owned paths, acceptance |
| `plan/STATE.md`, `plan/LOG.md`, `plan/FOLLOWUPS.md` | Live board, event log, parked minor findings |
| `plan/HUMAN_CHECKS.md` | Your checklist for after the run: phone tests, imports, phase checks |
| `plan/templates/` | Ticket, handoff, and review-checklist templates |
| `.claude/agents/` | 8 agent definitions with their models |
| `docs/DECISIONS.md` | Your answers to the open questions (fill before starting) |
| `docs/SPEC.md` | The spec, exported from the Claude Docs spec (rev 12, 2026-11-12) |

## Model roles
| Agent | Model | Does |
|---|---|---|
| orchestrator (main session) | Opus | Plans, dispatches, runs CI, merges, opens gates. Never writes code |
| `architect` | Fable | Contracts; sync and replay engines; second review on high-risk tickets |
| `writer-senior` | Opus | FSRS, queue builder, auth, parser, AnkiDroid stand-in, sync client |
| `writer` | Sonnet | Most feature tickets |
| `writer-light` | Haiku | Generated clients, fixtures, build scripts, docs |
| `reviewer` | Opus | Medium/high-risk reviews (diff only) |
| `reviewer-light` | Sonnet | Low-risk reviews |
| `ci-triage`, `scout` | Haiku | Summarise failing test logs; answer code questions in ≤ 150 words |

## Start
1. `git init`, copy these files in, commit.
2. Export the spec doc → `docs/SPEC.md`. Fill `docs/DECISIONS.md`.
3. Put `JPDB_API_KEY` in `.env.local` and the jpdb export at `fixtures/private/jpdb-export.json`
   (both git-ignored by T001; add them to `.gitignore` now if you start before T001 merges).
4. In the repo root: `claude --model opus`, then:
   `Read ORCHESTRATOR.md and start the build. GATE-0 is answered.`
5. **Single-session mode (default, non-blocking):** once `docs/SPEC.md` exists it never stops to ask.
   Contracts freeze after an automated review; open questions get provisional, reversible answers
   (marked PROVISIONAL in `docs/DECISIONS.md`); missing cluster facts become placeholders. It leaves
   you `plan/HUMAN_CHECKS.md` — contract review, provisional decisions, phone tests, imports, and
   phase checks. For a stop-at-every-phase run, set `Mode: gated` in `plan/STATE.md`.
6. To resume after a break or a limit reset: `Read ORCHESTRATOR.md and resume.`

## Where tokens go, and how this keeps them down
- Fable is used only for 6 tickets (contracts, sync, replay) plus second reviews on those areas.
- Writers read their ticket, named spec sections, and contracts only, in isolated worktrees.
- Only high-risk tickets are reviewed, from diffs. Haiku reads every failing test log so nobody else does.
- The orchestrator works from handoffs and one-line state; it persists everything to `plan/`
  so it can resume after context compaction without re-reading history.
- Escalation is capped: 2 attempts per tier, 5 total, then a human gate.

## Check before starting
- Model aliases in `.claude/agents/*.md` (`fable`, `opus`, `sonnet`, `haiku`) must match what your
  Claude Code version accepts. Change them there if needed.
- Subagents can't spawn subagents, so the orchestrator must be the main session.
- Toolchain on this machine (checked 2026-11-12): JDK 17, Node 26 + pnpm, Docker (daemon up), Helm 4,
  kubectl, Android SDK (platform 35, build-tools 35), ffmpeg, gh. **Install before starting:**
  `uv` (worker module; it fetches Python 3.12 itself), `restic` (T112), `k3d` (T009/T111/T112, AUR `k3d-bin`).
- Unattended runs: approve the build tools up front (auto mode, or an allowlist in `.claude/settings.json`),
  or writers stall on permission prompts.
- Blocking gates in single-session mode: none once `docs/SPEC.md` exists. Everything else is in
  `plan/HUMAN_CHECKS.md`.
