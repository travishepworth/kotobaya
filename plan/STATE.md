# STATE
Mode: single-session
Phase: P2 (Review loop)   Gate: GATE-P1 deferred (HUMAN_CHECKS); contracts frozen
Updated: 2026-11-12T15:45:51Z


## RESUME HERE (updated 2026-11-12T14:26:49Z)
- P1 done (18/18, make test green, GATE-P1 deferred). P2 tickets in plan/tickets/ (splits: T201a/b, T202a/b/c, T207a/b, T210a/b, T213a/b; C-4; T214). Per-ticket status: BACKLOG.md "Phase 2 splits" line.
- Next after running ones: T213a (after T201a), T202b (after T202a), T207a (after C-4), T203 (after T202a). High-risk (T202*, T203, T205, T207*) → reviewer (opus) + architect 2nd pass for sync/replay.
- Web tickets: tell writers SvelteKit 3 = relative imports (no $lib), client via web/src/lib/api/generated.ts (see FOLLOWUPS T208).
- Conventions: named agent types now registered; architect-role tickets run with model=opus (never Fable). Archive HANDOFF.md → plan/handoffs/<ID>.md at merge. MAX_PARALLEL_WRITERS=6, ≤3 Gradle-heavy. Review loop only on real majors with failure scenarios.

## Running
| Ticket | Agent | Attempt | Branch | Started |
|---|---|---|---|---|
| T213b | writer-senior (opus) | 1 | t/T213b-core-js-facade | 2026-11-12T15:44:18Z |
| T202a | ci-triage (check red after rebase) | 1 | t/T202a-sync-core-r | 2026-11-12T15:45:19Z |

## Ready for review / In review
| Ticket | Reviewer | Started |
|---|---|---|

## Blocked
| Ticket | Reason | Needs |
|---|---|---|

## Notes
- Project agent types ARE registered as of the 2026-11-12 restart; dispatch with the named agent types.
- Stale worktrees (all merged tickets + the 4 stopped runs; old branch names held, hence `-r` branches) still present: removal denied by permission classifier → in HUMAN_CHECKS.
- MAX_PARALLEL_WRITERS=6; ≤3 Gradle-heavy at once. Human (go hard): up to 3 concurrent reviewers allowed.
- Handoffs archived to plan/handoffs/<ID>.md after merge (keeps HANDOFF.md off main → no rebase conflicts).
- Model tiers (ADR-004): `fable` tickets → Opus with architect role; escalation tops out at Opus. No Fable agents at all (human: burns usage). Resumes of old Fable agents are forbidden — restart on Opus in the same worktree. Agents must report tool refusals as BLOCKED, not work around them.
- Review rounds (human, 2026-11-12): loop while findings are REAL — blocker/major with a concrete failure scenario (inputs → wrong result/data loss/divergence). Minors and style never block (→ FOLLOWUPS). Re-reviews verify prior findings + only genuinely new majors. Safety cap: §9 (5 attempts → BLOCKED_HUMAN).

## Pending human questions
- none blocking. See plan/HUMAN_CHECKS.md.

## Phase stats
P2: merged 6/23 · attempts 10 · escalations 0
P0 done: 15 merged (T001, T001b, T002, T003–T006, T008, T008b, T009, T009b, S01, S02, C-1, C-2).
Merged 14/15 (freeze review next) (T009b added) (S02, T008b added) (T007 dropped, T001b added) · attempts 13 · escalations 0

## Recently merged (last 10)
- T207a jpdb history import plan (worker): buckets property-tested, stable review ids, ADR-009 — reviewer APPROVE
- T213a card renderer + sanitiser (55 hostile payloads), 36 goldens jvm+js, card.css drift test
- T212 web settings/devices/PAT/QR pairing/attribution (web half; server half split → T212b, dep T202b)
- T208 web shell: login/session/nav/tokens/toasts/SSE; k3d dev HTTPS on :8443 (login verified)
- C-4 jpdb import contract aligned with ADR-009 (additive counters, bucket arithmetic for T207a)
- T201a note types + CardGenerator + template substitution (drift test vs V1 seeds)
- T111b deploy fixes: BACKUPS_DISABLED env, backup image, restore follow-ups (stamped pre-restore DB, DRILL-gated kill hook)
- T112 backups + restore drill — 3 attempts; reviewer APPROVE (watchdog, restore/backup exclusion, durable_seq under lock, crash-safe rename)
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
