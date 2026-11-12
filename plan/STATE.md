# STATE
Mode: single-session
Phase: P1 (Foundations)   Gate: GATE-CONTRACTS AUTO-CLOSED; contracts frozen
Updated: 2026-11-12T13:56:32Z (pre-restart handover)


## RESUME HERE (written 2026-11-12T13:56:32Z before a planned session restart)
- Phase 1: 16/18 merged. Remaining: **T112 backups** (high risk, sonnet) — was RUNNING in worktree .claude/worktrees/agent-af5ce7b42729bb029 on t/T112-backups (base 0d811c6, no commits at handover). If the agent is gone: if the branch has commits + HANDOFF.md → validate (paths, `make check MODULE=deploy`), then send to a reviewer (opus) — high risk; else re-dispatch T112 attempt 1 from plan/tickets/T112.md with the orchestrator notes in plan/LOG.md (SYNC 6.2–6.3 handshake, blob mirror after dump, "backups disabled" setting, k3d restore drill on cluster `kotobaya-t112`).
- Then: `make test` (all modules) → GATE-P1 deferred (single-session) → write P1 human checks into plan/HUMAN_CHECKS.md → start Phase 2. Phase 2 needs ticket files first (dispatch a sonnet planner like the P1 one: plan/tickets/T201.md… from BACKLOG + FOLLOWUPS + ADRs 002–009).
- Before T208 (web shell): fix k3d dev HTTPS (Secure cookie) — see FOLLOWUPS. Before T207: ADR-009 + JPDB_API_KEY live check (S02) + small C-ticket on JobResultJpdbHistory.
- Stale worktrees (merged): agent-ae408d50204f320d0 (T009), agent-af77a050ea16184ff (T111) are harness-locked; after restart run `git worktree remove -f -f <path>` and `git branch -D t/T009-helm t/T111-helm-workloads`.
- Working conventions this run (also in memory): dispatch via general-purpose + model override + inlined role (project agent types not registered); NO Fable (ADR-004, Opus for architect role); review loop only on real majors with a failure scenario; reviewers get verification passes via SendMessage to the same agent; Write tool refuses SUMMARY.md for subagents → orchestrator commits the text verbatim; agents must report tool refusals, never work around them; archive HANDOFF.md to plan/handoffs/<ID>.md after each merge; MAX_PARALLEL_WRITERS=6, ≤3 Gradle-heavy.

## Running
| Ticket | Agent | Attempt | Branch | Started |
|---|---|---|---|---|
| T112 | writer (gp/sonnet) | 1 | t/T112-backups | 2026-11-12T13:29:07Z |

## Ready for review / In review
| Ticket | Reviewer | Started |
|---|---|---|

## Blocked
| Ticket | Reason | Needs |
|---|---|---|

## Notes
- Project agent types not registered; dispatch = general-purpose + model override + inlined role (human OK'd).
- MAX_PARALLEL_WRITERS=6; ≤3 Gradle-heavy at once. Human (go hard): up to 3 concurrent reviewers allowed.
- Handoffs archived to plan/handoffs/<ID>.md after merge (keeps HANDOFF.md off main → no rebase conflicts).
- Model tiers (ADR-004): `fable` tickets → Opus with architect role; escalation tops out at Opus. No Fable agents at all (human: burns usage). Resumes of old Fable agents are forbidden — restart on Opus in the same worktree. Agents must report tool refusals as BLOCKED, not work around them.
- Review rounds (human, 2026-11-12): loop while findings are REAL — blocker/major with a concrete failure scenario (inputs → wrong result/data loss/divergence). Minors and style never block (→ FOLLOWUPS). Re-reviews verify prior findings + only genuinely new majors. Safety cap: §9 (5 attempts → BLOCKED_HUMAN).

## Pending human questions
- none blocking. See plan/HUMAN_CHECKS.md.

## Phase stats
P0 done: 15 merged (T001, T001b, T002, T003–T006, T008, T008b, T009, T009b, S01, S02, C-1, C-2).
Merged 14/15 (freeze review next) (T009b added) (S02, T008b added) (T007 dropped, T001b added) · attempts 13 · escalations 0

## Recently merged (last 10)
- T105b devices, pairing, PATs, scope enforcement — 2 attempts, reviewer APPROVE; revocation ends live streams (real wiring test)
- T111 Helm real workloads — k3d e2e: health, 401, login verified; dev needs HTTPS for Secure cookie
- W-1 wiring: auth + dict + jobs live in server (end-to-end WiringTest); worker dict-refresh job
- T105a auth: login, TOTP, sessions, proxy-aware rate limit — 2 attempts, reviewer APPROVE
- T109b worker job runner (1000 jobs × 3 runners exactly once, retries, crash reclaim, cancel)
- T109a jobs API, retention, SSE events (not yet wired — W-1)
- T106 dictionary search API (50 fixture queries) — handlers not yet wired in Main.kt (W-1 after T105a/T109a)
- T104 server skeleton (Ktor, jOOQ, Flyway, health/readiness, startup lock/restore/scheduler-version, 501 stubs, conformance test)
- T110 generated clients (TS, Kotlin, Python) + make check MODULE=contracts (4 checks + drift) — 2 attempts (haiku→sonnet)
- T103b worker matches DICT_PACKAGE.md — wrong-base/digest/missing-base rejections tested (60 worker tests)
- T108b search→SQL (Postgres + SQLite prefilter/residual) — 80 executed queries both dialects, DST day bounds
- C-3 dictionary package/diff contract (DICT_PACKAGE.md) — 2 attempts; content digests, published-base rule
- T107b FSRS scheduler — reviewer verified line-by-line vs py-fsrs 6.3.2; jvm+js identical
- T103 dictionary device package + diffs (zstd, S3 SigV4) — real size budget unmeasured
- C-2 freeze fixes — freeze A + B APPROVE; GATE-CONTRACTS auto-closed
- T107a PortableMath (fdlibm 5.3) — reviewer verified digests against StrictMath independently; jsTest on node wired
- T102 pitch/frequency/Tatoeba loaders — idempotent, coverage floors, CC0 test data
- T108a search parser/format/complete — examples, round trip, 3000 random trees
- T101 dictionary pipeline (JMdict/KANJIDIC2/KRADFILE → ref.*) — 13 tests incl. Postgres 17
- T006 core interfaces — 2 attempts; reviewer + architect APPROVE; PortableMath (fdlibm), search round-trip, queue rules
- C-1 db/sync contract alignment — 2 attempts; replay order, last_review_at, server_state, op versioning rule, FSRS bounds
- T004 OpenAPI contract — 3 attempts (1 Fable, 2 Opus); reviewer + architect APPROVE; 117 operations
- T005 sync contract — 4 attempts (1 Fable, 3 Opus); reviewer + architect APPROVE; restore detection, clock re-mint, op size cap
- T003 DB schema contract — 2 attempts; reviewer + architect APPROVE; 13 followups (T005 carries 8)
- T009b make check MODULE=deploy → helm check.sh (verified fails on broken chart)
- T001b+T002 SeaweedFS compose (dev-up all healthy), images/push/apk targets; apk fixed on attempt 2
- T008b jpdb fixture aligned to real export shape (invented values)
- T009 Helm chart + k3d — lint/template clean; k3d /healthz ok
- S02 jpdb map spike — 99.93% hit rate (GATE-S02 not triggered); jpdb vid == JMdict seq
- S01 AnkiDroid stand-in spike — both APK variants build; GATE-S01 deferred to HUMAN_CHECKS
- T008 golden fixtures — 240 FSRS vectors (py-fsrs 6.3.2), dict subsets, media, jpdb shape (unverified)
- T001 monorepo scaffold — make test green; compose minio unpullable → ADR-003/T001b
