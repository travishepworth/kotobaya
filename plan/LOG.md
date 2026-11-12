# LOG
<!-- <timestamp> <ID> <EVENT> <detail ≤ 100 chars> — append only -->
2026-11-12T09:22:03Z T001 DISPATCH writer(general-purpose, sonnet) attempt 1, branch t/T001-scaffold
2026-11-12T09:27:51Z - DECIDE MAX_PARALLEL_WRITERS=6 (human allowed >4; cap 3 concurrent Gradle-heavy writers)
2026-11-12T09:33:27Z T001 HANDOFF DONE; dev-up partial (minio unpullable, disk full)
2026-11-12T09:33:27Z T001 CHECK_GREEN make test
2026-11-12T09:33:27Z T001 MERGE 3f9b575
2026-11-12T09:33:27Z - DECIDE ADR-003 provisional: SeaweedFS replaces MinIO (images unpullable)
2026-11-12T09:33:27Z T001b+T002 DISPATCH writer-light(gp, haiku) batched
2026-11-12T09:33:27Z T003 DISPATCH architect(gp, fable)
2026-11-12T09:33:27Z T008 DISPATCH writer(gp, sonnet)
2026-11-12T09:33:27Z T009 DISPATCH writer(gp, sonnet)
2026-11-12T09:33:27Z S01 DISPATCH writer-senior(gp, opus)
2026-11-12T09:37:30Z T008 HANDOFF DONE
2026-11-12T09:37:30Z T008 CHECK_GREEN fixtures/tools pytest 3 passed
2026-11-12T09:37:30Z T008 MERGE
2026-11-12T09:37:54Z S02 DISPATCH writer(gp, sonnet); no API key, real export present (structure only)
2026-11-12T09:38:38Z S01 HANDOFF DONE
2026-11-12T09:38:38Z S01 CHECK_GREEN both variants assemble
2026-11-12T09:38:38Z S01 MERGE
2026-11-12T09:38:38Z GATE-S01 GATE_OPEN deferred to HUMAN_CHECKS (single-session)
2026-11-12T09:41:43Z S02 HANDOFF DONE hit rate 99.93%
2026-11-12T09:41:43Z S02 CHECK_GREEN spike pytest 3 passed; report privacy-scanned
2026-11-12T09:41:43Z S02 MERGE
2026-11-12T09:41:43Z GATE-S02 GATE_CLOSE hit rate >= 95%
2026-11-12T09:41:43Z T008b SPLIT fix jpdb fixture from S02 report
2026-11-12T09:41:43Z T002 CHECK_RED make apk fails; ci-triage dispatched
2026-11-12T09:42:03Z T002 DISPATCH attempt 2 (same writer); scope +android/build.gradle.kts, +.gitignore
2026-11-12T09:42:03Z T008b DISPATCH writer-light(gp, haiku)
2026-11-12T09:42:37Z T009 HANDOFF DONE
2026-11-12T09:42:37Z T009 CHECK_GREEN deploy/helm/check.sh
2026-11-12T09:42:37Z T009 MERGE
2026-11-12T09:43:05Z T008b HANDOFF DONE
2026-11-12T09:43:05Z T008b CHECK_GREEN fixtures pytest
2026-11-12T09:43:05Z T008b MERGE (backlog row added; earlier insert missed)
2026-11-12T09:43:52Z T002 HANDOFF DONE attempt 2
2026-11-12T09:43:52Z T002 CHECK_GREEN deploy+android checks, make apk
2026-11-12T09:43:52Z T001b+T002 MERGE
2026-11-12T09:43:52Z T009b DISPATCH writer-light(gp, haiku)
2026-11-12T09:44:36Z T009b HANDOFF DONE
2026-11-12T09:44:36Z T009b CHECK_GREEN check deploy
2026-11-12T09:44:36Z T009b MERGE
2026-11-12T09:55:03Z T003 HANDOFF DONE
2026-11-12T09:55:03Z T003 CHECK_GREEN contracts/db/check.sh (pg17 apply, sqlite parity, sqldelight)
2026-11-12T09:55:03Z T003 REVIEW dispatched reviewer(opus) + architect(fable)
2026-11-12T09:56:34Z T003 REVIEW_CHANGES reviewer: 4 major (entry_seq LWW vs live uq; merge register rule; list deck uq; grammar span cascade), 4 minor
2026-11-12T09:57:21Z T003 REVIEW_CHANGES architect 2nd pass: 5 major (merge rules, entry_seq, spans cascade+uq, deck uq), 1 minor
2026-11-12T09:57:21Z T003 DISPATCH attempt 2 (same architect) with merged findings (5 major, 5 minor)
2026-11-12T09:57:38Z - DECIDE review policy (human): one fix round max; re-review verifies listed findings only; non-blockers → FOLLOWUPS
2026-11-12T10:01:36Z - DECIDE review policy refined (human): keep fixing while findings are real majors/blockers; minors → FOLLOWUPS
2026-11-12T10:02:47Z T003 HANDOFF DONE attempt 2
2026-11-12T10:02:47Z T003 CHECK_GREEN contracts/db/check.sh
2026-11-12T10:02:47Z T003 REVIEW verification pass dispatched (both)
2026-11-12T10:04:05Z T003 REVIEW_APPROVE reviewer verification: all fixed/tradeoff, no new majors; 6 followups
2026-11-12T10:05:04Z T003 REVIEW_APPROVE architect verification: all 6 fixed, no new majors
2026-11-12T10:05:04Z T003 MERGE
2026-11-12T10:05:04Z T004 DISPATCH architect(gp, fable)
2026-11-12T10:05:04Z T005 DISPATCH architect(gp, fable); carries 8 T003 followups
2026-11-12T10:37:17Z T004 RESUME after 5h rate limit (agent interrupted, work saved)
2026-11-12T10:37:17Z T005 RESUME after 5h rate limit (agent interrupted, work saved)
2026-11-12T10:40:09Z - DECIDE ADR-004 (human): fable-tier tickets run on Opus; escalation tops at Opus
2026-11-12T10:45:58Z T005 HANDOFF DONE 45 ops, 14 scenarios
2026-11-12T10:45:58Z T005 CHECK_GREEN contracts/sync/check.sh
2026-11-12T10:45:58Z T005 REVIEW dispatched reviewer(opus) + architect(opus, ADR-004)
2026-11-12T10:51:04Z T005 REVIEW_CHANGES architect: 2 major (backup-restore log epoch/durable_seq; future-clock bound), 4 minor
2026-11-12T10:51:36Z T005 REVIEW_APPROVE reviewer (3 minor; #2 weights_version fold is a real divergence → fix)
2026-11-12T10:51:36Z T005 DISPATCH attempt 2 (same architect): 2 major + 2 must-fix minors + 5 cheap
2026-11-12T10:52:03Z T004 HANDOFF DONE 114 operations; sync bodies use a stand-in until T005 merges
2026-11-12T10:52:03Z T004 CHECK_GREEN contracts/api/check.sh
2026-11-12T10:52:03Z T004 REVIEW dispatched reviewer(opus); architect pass deferred (max 2 reviewers; T005 att.2 running)
2026-11-12T10:55:37Z T005 ESCALATE stopped Fable agent (usage); attempt 2 restarted on Opus in same worktree (no lost work)
2026-11-12T10:55:55Z T004 REVIEW_CHANGES reviewer: 7 major (undo/answer idempotency, sync envelope vs T005, push/pull semantics, bulk reset scope, auth exceptions), 3 minor; attempt 2 waits for T005
2026-11-12T11:08:35Z T005 HANDOFF DONE attempt 2 (opus)
2026-11-12T11:08:35Z T005 CHECK_GREEN 15 scenarios
2026-11-12T11:08:35Z T005 REVIEW architect verification dispatched (incl. clock-skew discard trade-off)
2026-11-12T11:10:45Z T005 REVIEW_CHANGES architect verification: 5/6 fixed; clock-reject discard-only = major (re-mint converges)
2026-11-12T11:10:45Z T005 DISPATCH attempt 3 (opus): re-mint + device guard + card-age check
2026-11-12T11:10:45Z T004 DISPATCH attempt 2 (opus) on branch rebased onto t/T005-sync
2026-11-12T11:17:57Z T004 HANDOFF DONE attempt 2 (opus), 117 ops
2026-11-12T11:17:57Z T004 CHECK_GREEN against real batch.schema.json
2026-11-12T11:17:57Z T004 REVIEW reviewer verification + architect 2nd pass dispatched
2026-11-12T11:18:33Z T004 REVIEW_APPROVE reviewer verification: 10/10 fixed, no new majors; 3 followups
2026-11-12T11:20:43Z T005 HANDOFF DONE attempt 3: re-mint, device guard, card-age check dropped
2026-11-12T11:20:43Z T005 CHECK_GREEN 16 scenarios
2026-11-12T11:20:43Z T005 REVIEW architect final verification dispatched
2026-11-12T11:22:10Z T005 REVIEW_CHANGES verification: re-mint fixed for existing cards; offline-created rows under clock_ahead still discard-only (major)
2026-11-12T11:22:10Z T005 DISPATCH attempt 4 (opus): re-mint creations + replay order (reviewed_at,hlc,id). Attempt 4 of cap 5.
2026-11-12T11:22:30Z T004 REVIEW_CHANGES architect 2nd pass: 1 major (no per-op size cap → 413 blocks outbox), 5 minor; sync side sent to T005 att. 4
2026-11-12T11:31:35Z T005 HANDOFF DONE attempt 4: re-mint creations, replay order (reviewed_at,hlc,id), max_op_bytes
2026-11-12T11:31:35Z T005 CHECK_GREEN
2026-11-12T11:31:35Z T005 REVIEW architect final verification (last round before cap)
2026-11-12T11:32:43Z T005 REVIEW_APPROVE architect final verification: fixed, no new majors; 6 followups
2026-11-12T11:32:43Z T005 MERGE
2026-11-12T11:33:10Z T004 DISPATCH attempt 3 (opus): op size/413, reviewed_at floor, lock, pair hlc_last, origin; align with T005 final
2026-11-12T11:33:34Z T006 DISPATCH architect(gp, opus per ADR-004)
2026-11-12T11:38:07Z T004 HANDOFF DONE attempt 3
2026-11-12T11:38:07Z T004 CHECK_GREEN 117 ops vs final T005
2026-11-12T11:38:07Z T004 REVIEW architect verification dispatched
2026-11-12T11:38:35Z T004 REVIEW_APPROVE architect verification: 6/6 fixed, no new majors; 3 followups
2026-11-12T11:38:35Z T004 MERGE
2026-11-12T11:54:43Z T006 HANDOFF BLOCKED only on Write-tool refusal of SUMMARY.md; orchestrator committed the architect's text verbatim
2026-11-12T11:54:43Z T006 CHECK_GREEN core, web, contracts/core/check.sh
2026-11-12T11:54:43Z T006 REVIEW reviewer + architect dispatched
2026-11-12T11:58:19Z T006 REVIEW_CHANGES architect: 1 major (cross-platform float determinism in FSRS pow/exp/round), 5 minor (2 need C-ticket on SCHEMA/SYNC)
2026-11-12T11:59:59Z T006 REVIEW_CHANGES reviewer: 3 major (disabled-deck cards introduced; Not(And) format; set_due elapsed), 7 minor
2026-11-12T11:59:59Z C-1 SPLIT contract-side fixes from T006 reviews (db+sync, non-breaking)
2026-11-12T11:59:59Z - DECIDE ADR-005 provisional: no collection-wide load balancing
2026-11-12T11:59:59Z - DECIDE ADR-006 provisional: mined cards priority 0 configurable; disabled decks introduce nothing
2026-11-12T11:59:59Z T006 DISPATCH attempt 2 (opus, same worktree)
2026-11-12T11:59:59Z C-1 DISPATCH architect(gp, opus)
2026-11-12T12:05:39Z C-1 HANDOFF DONE
2026-11-12T12:05:39Z C-1 CHECK_GREEN db + sync checks (22 invalid rejected)
2026-11-12T12:05:39Z C-1 REVIEW combined reviewer+architect (opus) dispatched (small non-breaking change)
2026-11-12T12:07:54Z C-1 REVIEW_CHANGES 2 major (app.job marker vs closed JobKind enum + retention), 4 minor
2026-11-12T12:07:54Z C-1 DISPATCH attempt 2 (same opus agent): server-only state table instead of app.job
2026-11-12T12:11:12Z T006 HANDOFF DONE attempt 2; orchestrator committed SUMMARY text (Write refusal again)
2026-11-12T12:11:12Z T006 CHECK_GREEN core/web/contracts-core (67 examples, 44 round trips)
2026-11-12T12:11:12Z C-1 HANDOFF DONE attempt 2; CHECK_GREEN
2026-11-12T12:11:12Z T006 REVIEW + C-1 REVIEW verification passes dispatched (3 reviewers, human said go hard)
2026-11-12T12:11:12Z - DISPATCH planner(sonnet): Phase 1 ticket files
2026-11-12T12:11:27Z C-1 REVIEW_APPROVE verification: 6/6 fixed
2026-11-12T12:11:27Z C-1 MERGE
2026-11-12T12:11:52Z T006 REVIEW_APPROVE reviewer verification: 8 fixed, 2 acceptable; 5 followups
2026-11-12T12:12:46Z T006 REVIEW_APPROVE architect verification: float determinism fixed
2026-11-12T12:12:46Z T006 CHECK_GREEN after rebase (all 4 contract checks + core)
2026-11-12T12:12:46Z T006 MERGE
2026-11-12T12:13:12Z GATE-CONTRACTS GATE_OPEN automated freeze review (split A: cross-check, B: P1–P3 coverage)
2026-11-12T12:16:12Z - SPLIT T105→a/b, T107→a/b, T108→a/b, T109→a/b (planner; >600 lines each)
2026-11-12T12:16:12Z - DECIDE ADR-007 provisional: server persistence = jOOQ; T111 may also edit deploy/k3d/ and docker-compose.yml
2026-11-12T12:17:39Z GATE-CONTRACTS REVIEW_CHANGES freeze B: 3 major (backup handshake keys, jpdb kanji import, kanji render data), 7 minor; waiting for A
2026-11-12T12:18:34Z GATE-CONTRACTS REVIEW_CHANGES freeze A: 2 major (duplicate keys in push; SCHEMA 5.3 stale purge/discard), 4 minor
2026-11-12T12:18:34Z C-2 DISPATCH architect(opus): freeze round 1 fixes (5 major, 8 minor)
2026-11-12T12:18:34Z T107a DISPATCH writer-senior(opus) (deps T006,T008 only)
2026-11-12T12:18:34Z T108a DISPATCH writer(sonnet) (deps T006 only)
2026-11-12T12:18:48Z T101 DISPATCH writer(sonnet) early: ref.* schema untouched by C-2
2026-11-12T12:24:47Z T101 HANDOFF DONE
2026-11-12T12:24:47Z T101 CHECK_GREEN worker 13 passed
2026-11-12T12:24:47Z T101 MERGE
2026-11-12T12:24:47Z T102 DISPATCH writer(sonnet)
2026-11-12T12:26:33Z T108a HANDOFF DONE
2026-11-12T12:26:33Z T108a CHECK_GREEN core+web after rebase
2026-11-12T12:26:33Z T108a MERGE (T108b waits for C-2: Search.kt compiler semantics)
2026-11-12T12:29:52Z T107a HANDOFF DONE 1.21M args bit-equal; jvm+js digests
2026-11-12T12:29:52Z T107a CHECK_GREEN core (jvmTest+jsTest) + web after rebase
2026-11-12T12:29:52Z T107a REVIEW reviewer(opus) dispatched
2026-11-12T12:30:14Z T102 HANDOFF DONE
2026-11-12T12:30:14Z T102 CHECK_GREEN worker
2026-11-12T12:30:14Z T102 MERGE
2026-11-12T12:30:14Z T103 DISPATCH writer(sonnet)
2026-11-12T12:32:29Z C-2 HANDOFF DONE
2026-11-12T12:32:29Z C-2 CHECK_GREEN all 4 contract checks + core + web after rebase
2026-11-12T12:32:29Z GATE-CONTRACTS freeze round 2: A + B verification dispatched
2026-11-12T12:32:59Z T107a REVIEW_APPROVE (4 minor → T107b ticket)
2026-11-12T12:32:59Z T107a MERGE
2026-11-12T12:32:59Z T107b DISPATCH writer-senior(opus)
2026-11-12T12:33:38Z GATE-CONTRACTS freeze A verification: APPROVE (6/6 fixed, C-2 additions consistent); waiting for B
2026-11-12T12:34:15Z C-2 REVIEW_APPROVE freeze A + B
2026-11-12T12:34:15Z C-2 MERGE
2026-11-12T12:34:15Z GATE-CONTRACTS GATE_CLOSE AUTO-CLOSED; contracts frozen (§10)
2026-11-12T12:34:15Z T104 DISPATCH writer(sonnet)
2026-11-12T12:34:15Z T108b DISPATCH writer(sonnet)
2026-11-12T12:34:15Z T110 DISPATCH writer-light(haiku)
2026-11-12T12:35:45Z T103 HANDOFF DONE
2026-11-12T12:35:45Z T103 CHECK_GREEN worker
2026-11-12T12:35:45Z T103 MERGE
2026-11-12T12:35:45Z C-3 DISPATCH architect(opus): dictionary package/diff format into contracts (T103 invented it)
2026-11-12T12:39:03Z - DECIDE ADR-009 (human): jpdb import JP→EN only, merge duplicate vids, known only when sole event, keywords editable
2026-11-12T12:40:23Z C-3 HANDOFF DONE; CHECK_GREEN db+api
2026-11-12T12:40:23Z C-3 REVIEW combined (opus) dispatched
2026-11-12T12:40:23Z T110 BLOCK kotlin/python generators fail on 3.1 shapes; TS ok
2026-11-12T12:40:23Z - DECIDE T110: ship TS; try other generators for kotlin/python, else tested post-gen patch; never change frozen contract for tooling
2026-11-12T12:40:23Z T110 ESCALATE haiku→sonnet attempt 2 (same worktree)
2026-11-12T12:41:40Z T107b HANDOFF DONE 26 scheduler tests jvm+js
2026-11-12T12:41:40Z T107b CHECK_GREEN core+web after rebase
2026-11-12T12:41:40Z T107b REVIEW reviewer(opus) dispatched
2026-11-12T12:42:38Z C-3 REVIEW_CHANGES 1 major (diff base must be the published full of from_release + content digest), 4 minor
2026-11-12T12:42:38Z C-3 DISPATCH attempt 2 (same opus agent)
2026-11-12T12:43:08Z T108b HANDOFF DONE but execution test missing (snapshots only)
2026-11-12T12:43:08Z T108b DISPATCH attempt 2 (same writer): sqlite+postgres execution tests, DST-correct day bounds
2026-11-12T12:44:25Z T107b REVIEW_APPROVE (6 minor → FOLLOWUPS)
2026-11-12T12:44:25Z T107b MERGE
2026-11-12T12:44:25Z C-3 HANDOFF DONE attempt 2; CHECK_GREEN
2026-11-12T12:44:25Z C-3 REVIEW verification dispatched
2026-11-12T12:45:12Z C-3 REVIEW_APPROVE verification
2026-11-12T12:45:12Z C-3 MERGE
2026-11-12T12:45:12Z T103b DISPATCH writer(sonnet): worker matches DICT_PACKAGE.md
2026-11-12T12:49:11Z T108b HANDOFF DONE attempt 2
2026-11-12T12:49:11Z T108b CHECK_GREEN core (incl. Postgres container) + web
2026-11-12T12:49:11Z T108b MERGE
2026-11-12T12:51:11Z T103b HANDOFF DONE
2026-11-12T12:51:11Z T103b CHECK_GREEN worker 60 tests
2026-11-12T12:51:11Z T103b MERGE
2026-11-12T12:53:14Z T110 HANDOFF DONE attempt 2
2026-11-12T12:53:14Z T110 CHECK_RED after rebase: drift from C-3 (check worked); orchestrator committed regenerated output
2026-11-12T12:53:14Z T110 CHECK_GREEN make check contracts
2026-11-12T12:53:14Z T110 MERGE
2026-11-12T12:54:21Z T104 HANDOFF DONE
2026-11-12T12:54:21Z T104 CHECK_GREEN server after rebase
2026-11-12T12:54:21Z T104 MERGE
2026-11-12T12:54:21Z T105a DISPATCH writer-senior(opus)
2026-11-12T12:54:21Z T106 DISPATCH writer(sonnet)
2026-11-12T12:54:21Z T109a DISPATCH writer(sonnet)
2026-11-12T13:02:24Z T106 HANDOFF DONE
2026-11-12T13:02:24Z T106 CHECK_GREEN server after rebase
2026-11-12T13:02:24Z T106 MERGE (wiring deferred to W-1)
2026-11-12T13:04:29Z T105a HANDOFF DONE
2026-11-12T13:04:29Z T105a CHECK_GREEN server after rebase
2026-11-12T13:04:29Z T105a REVIEW reviewer(opus) dispatched (flag: rate limiter behind ingress)
2026-11-12T13:06:01Z T105a REVIEW_CHANGES 1 major (limiter keys on ingress IP → global lockout), 4 minor
2026-11-12T13:06:01Z T105a DISPATCH attempt 2 (same writer): AUTH_TRUSTED_PROXIES + XFF, TOTP restart, argon2 mem cap
2026-11-12T13:07:01Z T109a HANDOFF DONE
2026-11-12T13:07:01Z T109a CHECK_GREEN server after rebase
2026-11-12T13:07:01Z T109a MERGE
2026-11-12T13:07:01Z T109b DISPATCH writer(sonnet)
2026-11-12T13:11:12Z T109b HANDOFF DONE
2026-11-12T13:11:12Z T109b CHECK_GREEN worker
2026-11-12T13:11:12Z T109b MERGE
2026-11-12T13:11:12Z W-1 SPLIT wiring ticket (server Main.kt + worker registry/CMD)
2026-11-12T13:11:12Z T111 DISPATCH writer(sonnet)
2026-11-12T13:11:28Z T105a REVIEW_APPROVE verification
2026-11-12T13:11:28Z T105a MERGE
2026-11-12T13:11:28Z T105b DISPATCH writer-senior(opus)
2026-11-12T13:11:28Z W-1 DISPATCH writer(sonnet)
2026-11-12T13:16:32Z W-1 HANDOFF DONE
2026-11-12T13:16:32Z W-1 CHECK_GREEN server+worker
2026-11-12T13:16:32Z W-1 MERGE
2026-11-12T13:29:07Z T111 HANDOFF DONE
2026-11-12T13:29:07Z T111 CHECK_GREEN deploy after rebase; orchestrator k3d e2e: health ok, unauth 401, login 200 (Secure cookie over http not resent → followup)
2026-11-12T13:29:07Z T111 MERGE
2026-11-12T13:29:07Z T112 DISPATCH writer(sonnet)
2026-11-12T13:29:43Z T105b REVIEW_CHANGES 2 major (revocation not wired into event streams in prod; pairing URI http behind TLS), 4 minor
2026-11-12T13:29:43Z T105b DISPATCH attempt 2 (same writer; paths +Main.kt composeApp, chart api.publicUrl)
2026-11-12T13:38:55Z T105b REVIEW_APPROVE verification (Main.kt + chart edits sanctioned by orchestrator for attempt 2)
2026-11-12T13:38:55Z T105b MERGE
2026-11-12T13:56:32Z - HANDOVER pre-restart; T112 running; next: make test → close P1 → P2 tickets
2026-11-12T13:58:14Z T112 HANDOFF DONE drill PASS
2026-11-12T13:58:14Z T112 CHECK_GREEN deploy after rebase
2026-11-12T13:58:14Z T112 REVIEW reviewer(opus) dispatched
2026-11-12T13:58:14Z T111b SPLIT SeaweedFS preallocation (filled / in drill), BACKUPS_DISABLED env, backup image
2026-11-12T14:01:02Z T112 REVIEW_CHANGES 3 major: backup can run during restore (partial dump tagged verified); durable_seq.sql lacks lock 7033001 (races finalization → durable_seq covers unbacked E2 seqs); job kills send no alert (needs staleness check). 6 minor (temp-DB restore, restic read-data, drill asserts, envFrom).
2026-11-12T14:01:02Z T112 DISPATCH attempt 2 (same writer); SeaweedFS preallocation fix moved from T111b into T112
2026-11-12T14:11:02Z T112 REVIEW_CHANGES verification: 8/9 fixed; new major: suspended CronJobs silence staleness alerts (needs watchdog); must-fix: crash between renames loses live DB name
2026-11-12T14:11:02Z T112 DISPATCH attempt 3 of 5 (same writer)
2026-11-12T14:20:10Z T112 REVIEW_APPROVE final verification
2026-11-12T14:20:10Z T112 MERGE
2026-11-12T14:20:10Z T111b extended with T112 follow-ups (sonnet)
2026-11-12T14:20:10Z - HANDOVER updated: next T111b → make test → close P1 → P2 tickets
2026-11-12T14:21:23Z - RESUME session restart; stale worktree cleanup denied by permission classifier (left in place)
2026-11-12T14:21:23Z T111b DISPATCH writer(sonnet) attempt 1
2026-11-12T14:21:23Z - DISPATCH planner(sonnet): Phase 2 ticket files
2026-11-12T14:22:23Z T111b DISPATCH restart (prior worktree auto-removed before any edits; same plan)
2026-11-12T14:23:44Z T111b HANDOFF DONE
2026-11-12T14:23:44Z T111b CHECK_GREEN deploy
2026-11-12T14:23:44Z T111b MERGE
2026-11-12T14:24:04Z - PHASE P1 all merged; make test running
2026-11-12T14:25:52Z - CHECK_GREEN make test (all modules) P1
2026-11-12T14:25:52Z GATE-P1 GATE_CLOSE deferred to HUMAN_CHECKS (single-session)
2026-11-12T14:26:18Z - SPLIT P2: T201a/b, T202a/b/c, T207a/b, T210a/b, T213a/b; C-4 added (planner)
2026-11-12T14:26:18Z - DECIDE T212 also owns settings/presets endpoints (unowned otherwise)
2026-11-12T14:26:18Z - DECIDE bulk card ops synchronous per frozen contract (≤5000 cards), not a job; backlog text superseded
2026-11-12T14:26:18Z - DECIDE add T214 web jpdb import screen (GATE-P2 needs import from web)
2026-11-12T14:26:49Z C-4 DISPATCH architect(opus)
2026-11-12T14:26:49Z T202a DISPATCH architect(opus)
2026-11-12T14:26:49Z T201a DISPATCH writer(sonnet)
2026-11-12T14:26:49Z T208 DISPATCH writer(sonnet)
2026-11-12T14:28:52Z T201a HANDOFF DONE
2026-11-12T14:28:52Z T201a CHECK_GREEN core
2026-11-12T14:28:52Z T201a MERGE
2026-11-12T14:29:08Z T213a DISPATCH writer-senior(opus)
2026-11-12T14:33:53Z C-4 HANDOFF DONE
2026-11-12T14:33:53Z C-4 CHECK_GREEN contracts after rebase
2026-11-12T14:33:53Z C-4 MERGE additive: 3 new JobResultJpdbHistory counters, ADR-009 import rules
2026-11-12T14:34:05Z T207a DISPATCH writer-senior(opus)
2026-11-12T14:34:55Z T208 HANDOFF DONE (k3d https login/logout verified)
2026-11-12T14:34:55Z T208 CHECK_GREEN web+deploy after rebase
2026-11-12T14:34:55Z T208 MERGE (web/Dockerfile 1-line out-of-path edit accepted: copies clients/typescript)
2026-11-12T14:35:11Z T212 DISPATCH writer(sonnet)
2026-11-12T14:35:23Z - HANDOVER usage limit: stopped T202a, T213a, T207a, T212 mid-work (uncommitted partial work may be in their worktrees); redispatch on resume
2026-11-12T15:33:37Z - RESUME after usage limit; worktree cleanup denied again by classifier (left in place)
2026-11-12T15:33:37Z T202a DISPATCH architect(opus) restart, branch -r suffix
2026-11-12T15:33:37Z T213a DISPATCH writer-senior(opus) restart, branch -r suffix
2026-11-12T15:33:37Z T207a DISPATCH writer-senior(opus) restart, branch -r suffix
2026-11-12T15:33:37Z T212 DISPATCH writer(sonnet) restart, branch -r suffix
2026-11-12T15:37:23Z T212 HANDOFF BLOCKED server half on T202b; web half done
2026-11-12T15:37:23Z T212 CHECK_GREEN web
2026-11-12T15:37:23Z T212 SPLIT server half → T212b (dep T202b)
2026-11-12T15:37:23Z T212 MERGE web half (new dep qrcode-generator, justified)
2026-11-12T15:43:34Z T213a HANDOFF DONE
2026-11-12T15:43:34Z T213a CHECK_GREEN core after rebase
2026-11-12T15:43:34Z T213a MERGE
2026-11-12T15:43:34Z T207a HANDOFF DONE (299 worker tests)
2026-11-12T15:44:18Z T207a CHECK_GREEN worker after rebase
2026-11-12T15:44:18Z T207a REVIEW dispatched reviewer(opus)
2026-11-12T15:44:18Z T213b DISPATCH writer-senior(opus)
2026-11-12T15:45:19Z T202a HANDOFF DONE (jvm drift test outside owned paths, flagged)
2026-11-12T15:45:19Z T202a CHECK_RED core after rebase onto T213a; ci-triage dispatched
2026-11-12T15:45:51Z T207a REVIEW_APPROVE 6 minors → FOLLOWUPS
2026-11-12T15:45:51Z T207a MERGE
2026-11-12T15:46:18Z T202a DISPATCH attempt 2 (same architect): jsTest lone-surrogate literals become '?' (3 tests)
2026-11-12T15:47:48Z T202a HANDOFF DONE attempt 2
2026-11-12T15:47:48Z T202a CHECK_GREEN core after rebase
2026-11-12T15:47:48Z T202a REVIEW dispatched reviewer(opus) + architect(opus) 2nd pass
2026-11-12T15:47:48Z T213b HANDOFF DONE
2026-11-12T15:47:48Z T213b CHECK_GREEN web after rebase
2026-11-12T15:47:48Z T213b MERGE
2026-11-12T15:49:42Z T202a REVIEW_CHANGES 1 major: schema patterns via kotlin Regex differ jvm vs js (\S vs U+3000, $ before trailing 
) → web pull stuck; 3 minors. Awaiting architect pass
2026-11-12T15:51:14Z T202a REVIEW_CHANGES architect: same major + depth-limit stuck-outbox (treated must-fix) + lenient seq rule; 2 contract minors → C-5
2026-11-12T15:51:14Z T202a DISPATCH attempt 3 (same architect agent)
2026-11-12T15:51:14Z C-5 DISPATCH architect(opus): ECMA pattern semantics in SYNC.md/validate.py, invalid examples, depth 512 (additive)
2026-11-12T15:56:50Z T202a HANDOFF DONE attempt 3 (PortableRegex, TooDeep per-op, encode depth limit)
2026-11-12T15:56:50Z T202a CHECK_GREEN core after rebase
2026-11-12T15:56:50Z T202a REVIEW dispatched verification re-review (reviewer opus)
2026-11-12T15:56:50Z - NOTE merge order: C-5 first, then T202a re-embeds new invalid examples (drift test)
2026-11-12T15:58:30Z T202a REVIEW_CHANGES verification: 5/5 prior fixed; new major: op depth == doc depth → op legal alone, 514 deep in pull body → pull stuck
2026-11-12T15:58:30Z DECIDE op max depth 500 (from op root), document max depth 512; C-5 told to pin both
2026-11-12T15:58:30Z T202a DISPATCH attempt 4 of 5 (same architect agent)
2026-11-12T16:01:37Z C-5 HANDOFF DONE (SYNC 2.1 ECMA patterns, depth op 500/doc 512, 7 new examples)
2026-11-12T16:01:37Z C-5 REVIEW dispatched reviewer(opus)
2026-11-12T16:01:37Z T202a told: container-count depth definition + new C-5 examples
2026-11-12T16:03:28Z C-5 REVIEW_APPROVE 4 minors → FOLLOWUPS
2026-11-12T16:03:28Z C-5 MERGE additive
2026-11-12T16:08:28Z T202a HANDOFF DONE attempt 4 (op 500/doc 512 container count, C-5 examples embedded)
2026-11-12T16:08:28Z T202a CHECK_GREEN core on top of C-5
2026-11-12T16:08:28Z T202a REVIEW dispatched verification re-review (attempt 4)
2026-11-12T16:09:23Z T202a REVIEW_APPROVE verification attempt 4 (all prior fixed, drift test byte-identical)
2026-11-12T16:09:23Z T202a MERGE 4 attempts
2026-11-12T16:09:43Z T202b DISPATCH architect(opus)
2026-11-12T16:09:43Z T203 DISPATCH architect(opus)
2026-11-12T16:16:54Z T203 HANDOFF DONE
2026-11-12T16:16:54Z T203 CHECK_GREEN core after rebase
2026-11-12T16:16:54Z T203 REVIEW dispatched reviewer(opus) + architect(opus) 2nd pass
2026-11-12T16:17:48Z T203 REVIEW_APPROVE reviewer: 4 minors → FOLLOWUPS; awaiting architect
2026-11-12T16:19:50Z T203 REVIEW_APPROVE architect 2nd pass (6 minors; 3 routed into T202c ticket)
2026-11-12T16:19:50Z T203 MERGE 1 attempt
2026-11-12T16:35:43Z T202b HANDOFF DONE (45 op types; merge detection deferred to T202c → interim 503 + rollback)
2026-11-12T16:35:43Z T202b CHECK_GREEN server after rebase
2026-11-12T16:35:43Z T202b REVIEW dispatched reviewer(opus) + architect(opus) 2nd pass
2026-11-12T16:39:22Z T202b REVIEW_APPROVE reviewer: 8 minors; wiring gated on T202c; awaiting architect
2026-11-12T16:42:58Z T202b REVIEW_CHANGES architect: 2 major — instant ≥ year 10000 → Postgres error → 500 → outbox wedged; merge seam mints h1≠h2 → loser deleted_at diverges
2026-11-12T16:42:58Z T202b DISPATCH attempt 2 (same agent) + 5 cheap minors
2026-11-12T16:42:58Z C-6 DISPATCH architect(opus): storable-values contract (breaking, no data) + doc minors from T203/C-5
2026-11-12T16:48:01Z T202b HANDOFF DONE attempt 2 (pg instant format, reserveHlc, media deck follow-up, minors)
2026-11-12T16:48:01Z T202b CHECK_GREEN server after rebase
2026-11-12T16:48:01Z T202b REVIEW dispatched architect verification
2026-11-12T16:50:20Z T202b REVIEW_APPROVE architect verification (2 majors + 3 minors fixed; 2 new minors)
2026-11-12T16:50:20Z T202b MERGE 2 attempts
2026-11-12T16:50:43Z T202c DISPATCH architect(opus)
2026-11-12T16:50:43Z T201b DISPATCH writer(sonnet)
2026-11-12T16:50:43Z - T204, T212b ready; queued for Gradle slot (C-6, T202c, T201b running)
2026-11-12T16:59:32Z T201b HANDOFF DONE
2026-11-12T16:59:32Z T201b CHECK_GREEN server after rebase
2026-11-12T16:59:32Z T201b MERGE
2026-11-12T16:59:32Z DECIDE ADR-011 provisional: no card backfill on optional-kind toggle
2026-11-12T16:59:32Z T204 DISPATCH writer(sonnet)
2026-11-12T17:02:56Z C-6 HANDOFF DONE (breaking; NO_NUL moved to codec check → core follow-up)
2026-11-12T17:02:56Z C-6 CHECK_GREEN contracts + core after rebase
2026-11-12T17:02:56Z C-6 REVIEW dispatched reviewer + architect (breaking §10)
2026-11-12T17:02:56Z - T202d added: core NO_NUL codec check + invalid-codec embedding (dep C-6; core sync paths, not T202c)
2026-11-12T17:02:56Z T212b DISPATCH writer(sonnet)
2026-11-12T17:03:26Z T212b HANDOFF CONTRACT_CHANGE_REQUEST (settings keys missing; unknown-key rule follows openapi)
2026-11-12T17:03:26Z T212b BLOCK BLOCKED_CONTRACT → C-7
2026-11-12T17:03:26Z C-7 DISPATCH architect(opus) additive SCHEMA §6 keys
2026-11-12T17:06:01Z C-6 REVIEW_CHANGES 1 major: op maximum_interval_days uncapped (2e9) → derived due_at past 9999/timestamptz range → push 500 forever; 3 minors (list_key/jpdb_id maxLength, NO_NUL → T202d, importer NUL-strip on ids). Awaiting architect
2026-11-12T17:07:11Z C-6 REVIEW_CHANGES architect: same interval major + merge would break server OpApplyTest (year ≥10000 instants now schema_invalid)
2026-11-12T17:07:11Z C-6 DISPATCH attempt 2 (cap maximum_interval_days 36500; owns OpApplyTest instants test; 6 minors)
2026-11-12T17:07:11Z C-7 HANDOFF DONE; sent back to also rename QueueBuilder KDoc key → scheduler.mined_priority + QueueInput add_kanji member
2026-11-12T17:08:29Z T204 HANDOFF DONE
2026-11-12T17:08:29Z T204 CHECK_GREEN server after rebase
2026-11-12T17:08:29Z T204 MERGE
2026-11-12T17:08:29Z T211 DISPATCH writer(sonnet)
2026-11-12T17:08:29Z - T205 ready but held for C-7 (QueueInput contract change)
2026-11-12T17:09:54Z C-7 HANDOFF DONE (SCHEMA §6 keys + QueueInput.decklessAddKanji, key renamed mined_priority)
2026-11-12T17:09:54Z C-7 CHECK_GREEN contracts + core after rebase
2026-11-12T17:09:54Z C-7 REVIEW dispatched reviewer(opus)
2026-11-12T17:11:36Z C-7 REVIEW_CHANGES 2 major: settings int validity undefined (1.0 web=1, android=default); invalid deck add_kanji present-vs-absent ambiguous → queue divergence
2026-11-12T17:11:36Z DECIDE settings int = mathematical integer in range (1.0 valid), else default; add_kanji only exact off/before/alongside else absent; kanji drawn only with a home new-source (ADR-006)
2026-11-12T17:11:36Z C-7 DISPATCH attempt 2 (same agent)
2026-11-12T17:17:07Z C-6 HANDOFF DONE attempt 2; CHECK_GREEN contracts+core+server; architect verify dispatched
2026-11-12T17:17:07Z T202c HANDOFF DONE; CHECK_GREEN server after rebase; reviewer + architect dispatched
2026-11-12T17:17:07Z C-7 HANDOFF DONE attempt 2 (int = binary64 integer in range; add_kanji exact strings); re-review queued
2026-11-12T17:17:07Z T211 HANDOFF DONE; sent back once (stray web/tests/tmp file); CHECK_GREEN web; MERGE
2026-11-12T17:18:57Z T202c REVIEW_APPROVE reviewer (6 minors); awaiting architect
2026-11-12T17:18:57Z C-6 REVIEW_APPROVE architect verification; reviewer verify (combined with C-7) running
2026-11-12T17:20:46Z C-6 REVIEW_APPROVE reviewer verification; MERGE (breaking; HUMAN_CHECKS line exists)
2026-11-12T17:20:46Z C-7 REVIEW_APPROVE verification; CHECK_GREEN contracts+core after rebase on C-6; MERGE
2026-11-12T17:20:46Z T202d DISPATCH architect(opus)
2026-11-12T17:20:46Z T205 DISPATCH writer-senior(opus)
2026-11-12T17:20:46Z T212b DISPATCH attempt 2 writer(sonnet)
2026-11-12T17:24:29Z T202c REVIEW_APPROVE architect 2nd pass (5 minors; 3 contract gaps → C-8)
2026-11-12T17:24:29Z T202c CHECK_GREEN server after rebase on C-6/C-7
2026-11-12T17:24:29Z T202c MERGE 1 attempt
2026-11-12T17:24:29Z C-8 DISPATCH architect(opus): engine refusal, BACKUPS_DISABLED toggle, §10.4 re-mint gaps
2026-11-12T17:29:49Z T212b HANDOFF DONE attempt 2; CHECK_GREEN server; MERGE
2026-11-12T17:29:49Z T202d REVIEW_APPROVE (3 minors); DECIDE skip architect 2nd pass (56-line impl of C-6 rule reviewed twice; reviewer: no conformance issue)
2026-11-12T17:29:49Z T202d MERGE
2026-11-12T17:29:49Z T205 HANDOFF DONE (35 rule tests + determinism property tests)
2026-11-12T17:30:37Z T205 CHECK_GREEN core after rebase; REVIEW dispatched reviewer(opus)
2026-11-12T17:30:37Z - W-2 added (wiring sync/notes/decks/settings into Main.kt; collected from FOLLOWUPS)
2026-11-12T17:30:37Z W-2 DISPATCH writer(sonnet)
2026-11-12T17:31:48Z T205 REVIEW_APPROVE (3 minors: loader drops non-live memberships + StudyDay DST → T206; add_kanji attribution → C-8)
2026-11-12T17:31:48Z T205 MERGE
2026-11-12T17:31:48Z T206 DISPATCH writer(sonnet)
2026-11-12T17:38:04Z W-2 HANDOFF DONE (StoryServer test helper edit outside paths accepted)
2026-11-12T17:38:04Z W-2 CHECK_GREEN server after rebase
2026-11-12T17:38:04Z W-2 MERGE
2026-11-12T17:44:11Z C-8 HANDOFF DONE (refused cache, durable_seq when backups off, re-mint list D, add_kanji attribution); CHECK_GREEN contracts+core+server; reviewer + architect dispatched
2026-11-12T17:44:11Z T206 HANDOFF DONE; CHECK_GREEN server; MERGE (Main.kt registration line allowed)
2026-11-12T17:44:11Z T207b DISPATCH writer-senior(opus)
2026-11-12T17:44:11Z T209 DISPATCH writer(sonnet)
2026-11-12T17:44:11Z T210a DISPATCH writer(sonnet)
2026-11-12T17:47:03Z C-8 REVIEW_CHANGES 1 major: re-mint discards e:<seq> deck.item_put + word.status of window notes → week of offline mining silently leaves its deck; 6 minors (discard list MUST, refused-card MUST skip, 6.3 step 2b, kanji deck newPerDay=0). Awaiting architect
2026-11-12T17:48:17Z C-8 REVIEW_CHANGES architect: same major (re-mint drops offline-mined deck items) + 6 minors; interim safe except backups disabled→enabled toggle
2026-11-12T17:48:17Z DECIDE re-mint: e:<seq> item ops + word.status of window notes → E (re-minted as n:<note>); discard list MUST be shown
2026-11-12T17:48:17Z DECIDE add_kanji kanji count against own home deck (may exceed), not drawing deck; vocab+kanji atomic; BEFORE = kanji first
2026-11-12T17:48:17Z C-8 DISPATCH attempt 2 (same agent)
2026-11-12T17:48:41Z T209 HANDOFF DONE; CHECK_GREEN web after rebase; MERGE (Editor.svelte import + stand-in CardView removal accepted)
2026-11-12T17:51:22Z T210a HANDOFF DONE; CHECK_GREEN server after rebase; MERGE (Main.kt registration line allowed)
2026-11-12T17:51:22Z T210b DISPATCH writer(sonnet)
2026-11-12T17:55:02Z T210b HANDOFF DONE; CHECK_GREEN web after rebase; MERGE
2026-11-12T17:55:02Z - W-3 added (web nav links; layout unowned by P2 tickets)
2026-11-12T17:55:02Z W-3 DISPATCH writer-light(haiku)
2026-11-12T17:55:02Z T204 SPOT_CHECK reviewer-light (§6 every 10th merged med)
2026-11-12T17:56:01Z T204 SPOT_CHECK no majors; 7 minors → F-1
2026-11-12T17:56:01Z F-1 DISPATCH writer(sonnet)
2026-11-12T17:56:26Z W-3 HANDOFF DONE; CHECK_GREEN web; MERGE
2026-11-12T17:59:01Z T207b HANDOFF CONTRACT_CHANGE_REQUEST (server side done; internal job kind + reviews_id_collisions); CHECK_GREEN server; reviewer dispatched
2026-11-12T17:59:01Z C-9 DISPATCH architect(opus) additive
2026-11-12T17:59:01Z - T207c added (worker fetch_blob/apply_plan + Main.kt ImportsModule; deps T207b, C-9)
2026-11-12T17:59:01Z C-8 HANDOFF DONE attempt 2; architect verification dispatched
2026-11-12T17:59:16Z F-1 HANDOFF DONE; CHECK_GREEN server; MERGE
2026-11-12T18:00:53Z T207b REVIEW_APPROVE (6 minors → T207c, owned paths widened to server imports/)
2026-11-12T18:00:53Z T207b MERGE
2026-11-12T18:00:53Z DECIDE jpdb re-run skips groups whose reviews sit on user-deleted cards (no resurrection)
2026-11-12T18:01:02Z T214 DISPATCH writer(sonnet)
2026-11-12T18:02:18Z C-8 REVIEW_CHANGES verification: 7/8 fixed; new majors: item_removed e:→n: re-key loses removal; QueueBuilder rule 4 formula contradicts attribution
2026-11-12T18:02:18Z C-8 DISPATCH attempt 3 of 5 (same agent)
2026-11-12T18:03:24Z T214 HANDOFF DONE; CHECK_GREEN web; MERGE (nav link + i18n key outside paths accepted)
2026-11-12T18:06:19Z C-9 HANDOFF DONE; CHECK_GREEN contracts after rebase; reviewer dispatched
2026-11-12T18:06:19Z - T207c extended: jobs/ internal-kind filtering + worker step 2 / reviews_id_collisions (from C-9)
2026-11-12T18:09:08Z C-9 REVIEW_APPROVE (8 minors: 6 → T207c, 2 doc → FOLLOWUPS); MERGE
2026-11-12T18:09:08Z T207c DISPATCH writer(sonnet)
2026-11-12T18:09:08Z C-8 HANDOFF DONE attempt 3; CHECK_GREEN contracts+core+server; architect verification dispatched
2026-11-12T18:10:55Z C-8 REVIEW_CHANGES verification att 3: A/B/minors fixed, no regressions; new major F1 (pre-window e:s + window put + remove → removal re-keyed only to n:, e:s survives)
2026-11-12T18:10:55Z C-8 DISPATCH attempt 4 of 5 (double removal n:+e:)
2026-11-12T18:10:55Z RETRO C-8: 4 attempts, each round narrows re-mint deck-item keying edge cases; same root (8.5 effective identity vs per-key removal). Converging; no split.
2026-11-12T18:17:08Z C-8 HANDOFF DONE attempt 4 (re-keyed removal minted on n: and e:); CHECK_GREEN contracts+core+server; architect verification dispatched
2026-11-12T18:18:29Z T207c HANDOFF DONE; CHECK_GREEN worker + server after rebase; MERGE (2 small unowned edits accepted)
2026-11-12T18:19:26Z C-8 REVIEW_APPROVE architect verification attempt 4 (1 cosmetic minor)
2026-11-12T18:19:26Z C-8 MERGE 4 attempts
2026-11-12T18:19:26Z - F-2 (core), F-3 (server), F-4 (deploy) added from C-8 follow-ups; Android parts → Phase 3 FOLLOWUPS
2026-11-12T18:19:26Z F-2 DISPATCH writer-senior(opus)
2026-11-12T18:19:26Z F-3 DISPATCH architect(opus)
2026-11-12T18:19:26Z F-4 DISPATCH writer(sonnet)
2026-11-12T18:23:33Z F-4 HANDOFF DONE; CHECK_GREEN deploy; MERGE (k3d drill → HUMAN_CHECKS)
2026-11-12T18:27:31Z F-2 HANDOFF DONE; CHECK_GREEN core after rebase; reviewer dispatched
2026-11-12T18:29:43Z F-2 REVIEW_APPROVE (3 minors; ALONGSIDE-after-answer contract gap → P3 C-ticket, provisional default logged)
2026-11-12T18:29:43Z F-2 MERGE
2026-11-12T18:29:43Z DECIDE (provisional, P3) add_kanji K of a word introduced today stay drawable today
2026-11-12T18:38:45Z F-3 HANDOFF DONE (Startup.kt raiseOnStartup added on request); CHECK_GREEN server; reviewer dispatched
2026-11-12T18:40:58Z F-3 REVIEW_APPROVE (5 minors; architect pass not needed per reviewer)
2026-11-12T18:40:58Z F-3 MERGE; backups-toggle hazard removed from HUMAN_CHECKS
2026-11-12T18:40:58Z - PHASE P2 all merged; make test running
2026-11-12T18:43:02Z - CHECK_GREEN make test (all modules) P2
2026-11-12T18:43:02Z GATE-P2 GATE_CLOSE deferred to HUMAN_CHECKS (single-session)
2026-11-12T18:43:02Z RETRO P2: ~2.0 attempts/ticket. Overruns all in contract/sync (C-6 2, C-7 2, C-8 4, T202a 4, T202b 2): root cause = contract allowed values/edge cases that platforms or Postgres treat differently (regex semantics, depth, storable values, re-mint keying). Fixed at contract level (C-5..C-9). Non-sync tickets ~1.1. Keep: architect verification on every sync contract change; require concrete failure scenarios.
2026-11-12T18:49:57Z - SPLIT P3 (planner): T301a/b, T303a–e, T304a–c, T305a–c; 22 tickets
2026-11-12T18:49:57Z - DECIDE DECIDE P3-1: Android paths use the real layout android/src/main/kotlin/kotobaya/android/... (the module is :android, no app/ subdir); BACKLOG rows say android/app/..., superseded b
2026-11-12T18:49:57Z - DECIDE DECIDE P3-2: T303 split into a (core protocol/outbox/mint, pure core, no Android deps so it can start immediately), b (device op apply, replay, rebuild, log reset, refused cache), 
2026-11-12T18:49:57Z - DECIDE DECIDE P3-3: Refused-cache handling is folded into T303b (device apply writes the nil-UUID cache, diagnostics) and T304a (queue loader excludes refused cards), not a separate ticke
2026-11-12T18:49:57Z - DECIDE DECIDE P3-4: T305 split into a (flavors + provider layer against an AnkiStore interface), b (NoteCreator: JMdict match, duplicates, ops; shared by T306/T311) and c (Takoboto Vocab 
2026-11-12T18:49:57Z - DECIDE DECIDE P3-5: T304 split into a (queue loader, study-day, counts/forecast), b (review screen: WebView, grading, undo), c (toolbar actions). Real deps: b/c need T303c (minting + loca
2026-11-12T18:49:57Z - DECIDE DECIDE P3-6: Shared Android files (nav, DI, build.gradle.kts, manifest) use a FeatureEntry registry in T301a; each ticket adds one registration line in di/FeatureModules.kt and its
2026-11-12T18:49:57Z - DECIDE DECIDE P3-7: T307 depends on T308 (DueTrigger listener seam) so the widget refresh and notification share one alarm; T307/T308 also depend on T304a for counts.
2026-11-12T18:49:57Z - DECIDE DECIDE P3-8: T313 adds an `acceptance` Gradle module (settings.gradle.kts include and Makefile MODULES/check-acceptance lines); CLAUDE.md's module table needs a human line. Server 
2026-11-12T18:49:57Z - DECIDE DECIDE P3-9: T302 may add a new core/.../kotobaya/dict/ package (romaji-to-hiragana parity with server, per FOLLOWUPS T106) rather than leaving it Android-only; server copy untouch
2026-11-12T18:49:57Z T301a DISPATCH writer(sonnet)
2026-11-12T18:49:57Z T303a DISPATCH writer-senior(opus)
2026-11-12T18:54:38Z T301a HANDOFF DONE (new deps: Koin, Compose, SQLDelight 2.0.2, Robolectric — justified); CHECK_GREEN android; MERGE (android/src/main/sqldelight/ outside paths accepted)
2026-11-12T18:54:38Z - T301b gets token-encryption follow-up (plain SharedPreferences)
2026-11-12T18:54:38Z T301b DISPATCH writer(sonnet)
2026-11-12T18:54:38Z T304a DISPATCH writer(sonnet)
2026-11-12T18:54:38Z - T305a queued (Gradle cap)
2026-11-12T18:59:51Z T301b HANDOFF DONE (okhttp, play-services-code-scanner deps justified); CHECK_GREEN android; MERGE
2026-11-12T18:59:51Z DECIDE ADR-012 provisional: hand-written OkHttp client on Android (generated client Kotlin version mismatch)
2026-11-12T18:59:51Z T305a DISPATCH writer-senior(opus)
2026-11-12T19:00:24Z T305a DISPATCH writer-senior(opus)
2026-11-12T19:00:24Z T304a HANDOFF DONE; rebase conflict FeatureModules.kt (T301b line) → back to writer to rebase
2026-11-12T19:01:10Z T304a HANDOFF DONE after rebase; CHECK_GREEN android; MERGE
2026-11-12T19:01:26Z T302 DISPATCH writer(sonnet)
2026-11-12T19:01:26Z - T308 ready, queued (Gradle cap)
2026-11-12T19:12:36Z T302 HANDOFF DONE (deps zstd-jni, work-runtime justified); CHECK_GREEN android + core; MERGE
2026-11-12T19:12:36Z T303a HANDOFF DONE; CHECK_GREEN core; reviewer + architect dispatched
2026-11-12T19:12:36Z T305a HANDOFF DONE (both flavors build; make apk both)
2026-11-12T19:13:25Z T305a CHECK_GREEN android after rebase on T302; reviewer dispatched
2026-11-12T19:13:25Z T308 DISPATCH writer(sonnet)
2026-11-12T19:15:31Z T305a REVIEW_CHANGES 1 major: provider silently falls back to in-memory store (cold start before startKoin) → Takoboto words reported saved then lost; 7 minors
2026-11-12T19:15:31Z T305a DISPATCH attempt 2 (same agent)
2026-11-12T19:15:41Z T303a REVIEW_CHANGES 1 major: >24h clock block lacks §3.4 hysteresis (resumes minting at skew ≤24h instead of <5min) → future HLCs win LWW over later edits; 5 minors. Awaiting architect
2026-11-12T19:18:06Z T303a REVIEW_CHANGES architect: log-reset livelock (contract: reset keeps pull_seq → repeated LogChanged, rebuild never ends) + no mint_at seam for T303d Re-mint; plus §3.4 hysteresis (both)
2026-11-12T19:18:06Z DECIDE log reset sets pull_seq=0 (C-10)
2026-11-12T19:18:06Z T303a DISPATCH attempt 2 (same agent)
2026-11-12T19:18:06Z C-10 DISPATCH architect(opus)
2026-11-12T19:19:41Z T308 HANDOFF DONE; CHECK_GREEN android after rebase; MERGE
2026-11-12T19:19:41Z T307+T309 DISPATCH writer(sonnet) batched (low risk, same module)
2026-11-12T19:20:45Z T305a HANDOFF DONE attempt 2 (no in-memory fallback, AnkiStoreHolder wait 5s → ISE); rebase conflict AndroidManifest (T308) → back to writer
2026-11-12T19:23:09Z T305a REVIEW_APPROVE verification (3 minors; AnkiStoreHolder.set obligation folded into T305c)
2026-11-12T19:23:09Z T305a MERGE 2 attempts
2026-11-12T19:23:22Z T312 DISPATCH writer-light(haiku)
2026-11-12T19:24:42Z T303a HANDOFF DONE attempt 2 (clock_blocked hysteresis, resetLog pull_seq=0, mintAll at); CHECK_GREEN core; architect verification dispatched
