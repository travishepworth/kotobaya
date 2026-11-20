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
2026-11-12T19:25:41Z C-10 HANDOFF DONE (6.2 reset sets pull_seq=0; model livelock guard; 12.5.1); review folded into T303a architect verification
2026-11-12T19:27:01Z C-10 REVIEW_APPROVE (architect, during T303a verification); MERGE
2026-11-12T19:27:01Z T303a REVIEW_APPROVE verification attempt 2; CHECK_GREEN core after rebase on C-10; MERGE
2026-11-12T19:27:01Z - T303d gets Re-mint Refused-rollback acceptance; T303b gets reset/auto-retry seam notes
2026-11-12T19:27:01Z T303b DISPATCH writer-senior(opus)
2026-11-12T19:27:26Z T312 HANDOFF BLOCKED (versionCode/Name in defaultConfig outside paths) → DECIDE approve env-driven version + .gitignore keystores + Makefile comment; attempt 1 continued
2026-11-12T19:29:42Z T312 HANDOFF DONE attempt 2; CHECK_GREEN android; MERGE (approved out-of-path: defaultConfig version, .gitignore, Makefile comment)
2026-11-12T19:31:41Z T307+T309 HANDOFF DONE (android/server/deploy green); asked writer to wire NtfySink in Main.kt before merge
2026-11-12T19:31:49Z - HANDOVER usage limit: T303b and T307+T309 (Main.kt wiring) in flight; see STATE.md USAGE-LIMIT STOP
2026-11-12T19:35:34Z T307+T309 HANDOFF DONE (Main.kt NtfySink wired); CHECK_GREEN android+server+deploy; MERGE
2026-11-13T00:24:42Z T303b DISPATCH attempt 2 writer-senior(opus) in same worktree (attempt 1 cut off by usage limit, partial uncommitted work kept)
2026-11-13T00:42:19Z T303b HANDOFF DONE attempt 2 (+3234 lines, owned paths only); reviewer + architect dispatched; own make check running
2026-11-13T00:42:26Z T303b CHECK_GREEN core (orchestrator re-run)
2026-11-13T00:44:45Z T303b REVIEW_APPROVE architect (6 minors → FOLLOWUPS; auto_status re-derivation folded into T304b)
2026-11-13T00:45:52Z T303b REVIEW_APPROVE reviewer (6 minors → FOLLOWUPS)
2026-11-13T00:46:19Z T303b MERGE 2 attempts
2026-11-13T00:46:19Z T303c DISPATCH writer-senior(opus)
2026-11-13T00:46:19Z T303d DISPATCH writer-senior(opus)
2026-11-13T01:07:15Z T303c HANDOFF DONE (+2575; flagged: FeatureModules line, work-testing test dep); reviewer + architect dispatched
2026-11-13T01:07:20Z T303c CHECK_GREEN android (orchestrator re-run)
2026-11-13T01:07:37Z T303d HANDOFF DONE (+2184, owned paths only); reviewer dispatched (architect queued: reviewer cap 3)
2026-11-13T01:07:42Z T303d CHECK_GREEN core (orchestrator re-run)
2026-11-13T01:09:26Z T303c REVIEW_APPROVE architect (6 minors → FOLLOWUPS/T304b/T303e); T303d architect dispatched
2026-11-13T01:09:33Z T303c REVIEW_APPROVE reviewer (5 minors → FOLLOWUPS/T304b)
2026-11-13T01:10:04Z T303d REVIEW_APPROVE reviewer (3 minors → FOLLOWUPS/T303e)
2026-11-13T01:10:04Z T303c MERGE 1 attempt
2026-11-13T01:10:04Z T304b DISPATCH writer(sonnet)
2026-11-13T01:10:04Z T305b DISPATCH writer-senior(opus)
2026-11-13T01:10:04Z T310 DISPATCH writer(sonnet)
2026-11-13T01:12:09Z T303d REVIEW_APPROVE architect (4 minors → FOLLOWUPS)
2026-11-13T01:12:09Z T303d MERGE 1 attempt
2026-11-13T01:14:48Z T310 HANDOFF DONE (+689); rebase conflict FeatureModules (T303c) → back to writer
2026-11-13T01:14:48Z W-4 created (T310 wiring gaps; deps T310,T304b,T303e)
2026-11-13T01:16:06Z T310 CHECK_GREEN android; MERGE 1 attempt (+rebase fix)
2026-11-13T01:16:06Z T303e DISPATCH writer(sonnet)
2026-11-13T01:20:43Z T304b HANDOFF attempt 1 built on stale base (worktree cut from unpushed origin/main) → attempt 2: rebase + wire T303c store
2026-11-13T01:20:43Z T305b told to rebase (same stale base)
2026-11-13T01:20:43Z F-5 created: server AutoStatusHook NoOp → device/server status differ (deps T304b)
2026-11-13T01:22:02Z T305b HANDOFF DONE (+1148, rebased on T303c); reviewer + architect dispatched
2026-11-13T01:22:13Z T305b CHECK_GREEN android (orchestrator re-run)
2026-11-13T01:23:16Z T305b REVIEW_APPROVE architect (6 minors → FOLLOWUPS)
2026-11-13T01:23:37Z T303e HANDOFF DONE; CHECK_GREEN android after rebase; MERGE 1 attempt
2026-11-13T01:24:06Z T305b REVIEW_APPROVE reviewer (6 minors → FOLLOWUPS/T305c/T311)
2026-11-13T01:24:14Z T305b rebase conflict FeatureModules (T303e) → back to writer
2026-11-13T01:25:40Z T304b CHECK_GREEN core+android after rebase; MERGE 2 attempts
2026-11-13T01:25:40Z T304c+W-4 DISPATCH writer(sonnet) batched
2026-11-13T01:25:40Z F-5 DISPATCH writer(sonnet)
2026-11-13T01:27:29Z T305b CHECK_GREEN android after rebase; MERGE 1 attempt (+2 rebase bounces)
2026-11-13T01:27:29Z T305c DISPATCH writer-senior(opus)
2026-11-13T01:34:25Z T304c+W-4 CHECK_GREEN android after rebase; MERGE 1 attempt
2026-11-13T01:34:25Z F-5 CHECK_GREEN server after rebase; MERGE 1 attempt
2026-11-13T01:34:25Z T313 DISPATCH writer-senior(opus)
2026-11-13T01:49:50Z T305c HANDOFF DONE; reviewer dispatched
2026-11-13T01:49:50Z F-6 created+DISPATCH writer(sonnet): sync DB schema missing if first use is a tx (found by T305c)
2026-11-13T01:50:14Z T305c CHECK_GREEN android after rebase
2026-11-13T01:52:36Z T305c REVIEW_APPROVE reviewer (8 minors → FOLLOWUPS)
2026-11-13T01:52:36Z DECIDE ADR-013 provisional: Takoboto note type UUIDv8 fixed id
2026-11-13T01:52:36Z T305c MERGE 1 attempt
2026-11-13T01:52:36Z T306+T311 DISPATCH writer(sonnet) batched
2026-11-13T01:54:26Z F-6 CHECK_GREEN android after rebase; MERGE 1 attempt
2026-11-13T02:03:36Z T306+T311 HANDOFF DONE; CHECK_GREEN android after rebase; MERGE 1 attempt
2026-11-13T02:03:36Z T313 HANDOFF DONE (100 seeds 0 lost reviews, no bugs); READY_FOR_REVIEW — handed over (context budget)
2026-11-13T02:03:36Z - HANDOVER context budget: nothing in flight; next T313 check+reviews+merge, then make test, GATE-P3 deferred, P4
2026-11-13T02:05:20Z T313 rebased on main; make check acceptance; reviewer + architect(opus) DISPATCH
2026-11-13T02:05:20Z - BOARD reconciled: P2/P3 parent rows SPLIT/MERGED; W-4/F-5/F-6 moved to P3; P3 = 25 tickets
2026-11-13T02:05:20Z P4 DISPATCH planner(opus) for ticket files/splits
2026-11-13T02:05:44Z T313 REVIEW_APPROVE reviewer (5 minors → FOLLOWUPS)
2026-11-13T02:06:10Z T313 REVIEW_APPROVE architect (4 new minors → FOLLOWUPS)
2026-11-13T02:07:05Z T313 CHECK_GREEN acceptance; MERGE 1 attempt — P3 complete
2026-11-13T02:12:39Z - CHECK_RED make test: worker DiskFull (env, 95% disk); prune denied → HUMAN_CHECKS; rerunning modules individually
2026-11-13T02:15:38Z - make test green (core, server, acceptance, worker, web, android, deploy, contracts; worker rerun after DiskFull)
2026-11-13T02:15:38Z GATE-P3 GATE_CLOSE deferred → HUMAN_CHECKS (device week offline, Takoboto, AnkiConnect)
2026-11-13T02:15:38Z - housekeeping: 66 merged worktrees+branches removed; disk 10G free
2026-11-13T02:17:06Z P4 SPLIT planner: 25 tickets (C-11,C-12,F-7,T401a..T411c,W-5); ADR-014..019 provisional
2026-11-13T02:17:06Z - DECIDE ADR-014..019 (P4 media pipeline defaults)
2026-11-13T02:17:37Z C-11,F-7,T401a,T402a,T404c,T411a DISPATCH (architect-opus, writer×4, writer-senior)
2026-11-13T02:22:39Z T402a CHECK_GREEN worker; MERGE 1 attempt
2026-11-13T02:22:39Z T401a HANDOFF DONE; T411a HANDOFF DONE; checks running
2026-11-13T02:22:39Z T402b+T409 DISPATCH writer(sonnet) batched; T405a DISPATCH writer(sonnet)
2026-11-13T02:22:53Z T401a CHECK_GREEN worker; MERGE 1 attempt
2026-11-13T02:23:05Z T411a CHECK_GREEN server; MERGE 1 attempt (W-5 wires it)
2026-11-13T02:26:01Z T405a CHECK_GREEN web; MERGE 1 attempt
2026-11-13T02:26:01Z F-7 CHECK_GREEN worker+fixtures/tools; MERGE 1 attempt (hand-authored segmentation; spot-check → HUMAN_CHECKS)
2026-11-13T02:26:13Z T403a DISPATCH writer-senior(opus)
2026-11-13T02:41:32Z - BLOCK host disk 100% (Docker anon volumes 35 GB; prune denied) → HUMAN_CHECKS; writers C-11/T404c/T403a told to stop BLOCKED
2026-11-13T02:42:11Z T403a,T404c,C-11 BLOCK disk full (work done; parked); T402b+T409 HANDOFF DONE (check pending)
2026-11-13T02:42:11Z F-8 created: server Flyway copies only V1 (C-11 adds V2)
2026-11-13T02:42:11Z - HANDOVER: disk full blocks all checks; nothing in flight
2026-11-13T03:11:14Z T402b+T409 CHECK_GREEN worker; MERGE 1 attempt
2026-11-13T03:13:15Z T403a CHECK_GREEN worker; REVIEW_APPROVE (5 minors → FOLLOWUPS); MERGE 1 attempt
2026-11-13T03:17:51Z C-11 CHECK_GREEN contracts; REVIEW_CHANGES reviewer+architect (1 major: lock-order deadlock §5.4); DISPATCH attempt 2 architect(opus)
2026-11-13T03:17:51Z T404c CHECK_RED server: OOM in test JVM under concurrent load (triage: flaky-infra); re-run when idle
2026-11-13T03:17:51Z F-8+F-9 HANDOFF DONE (root cause: worker dict conftest docker rm without -v); checks re-running
2026-11-13T03:18:29Z F-8+F-9 CHECK_GREEN server+worker; MERGE 1 attempt
2026-11-13T03:20:58Z C-11 REVIEW_APPROVE reviewer+architect (attempt 2)
2026-11-13T03:22:15Z C-11 CHECK_GREEN contracts; MERGE 2 attempts
2026-11-13T03:23:08Z T404c CHECK_RED again: server test JVM OOM (default 512m heap + uncached ApiSpec.load) — pre-existing; F-11 created; T404c waits on F-11
2026-11-13T03:23:08Z C-12,T411c,T411b,T404a,T407 DISPATCH (architect-opus, writer-senior, writer×2, writer-light)
2026-11-13T03:24:07Z - DECIDE ADR-020 (EDRDG checksum pinning dropped; optional pin)
2026-11-13T03:24:07Z - human-reported gaps → tickets D-1 (HTTPRoute+worker mem), F-12 (resumed refresh builds pkg), F-13 (ADR-020), T412 (web dict), T413 (web notes); W-5 split pending 501 audit
2026-11-13T03:24:50Z W-5a created (URGENT: blobs unwired + dict refresh/release never implemented); next free slot
2026-11-13T03:26:55Z T407 MERGE CHECK_GREEN web; 1 attempt (low)
2026-11-13T03:29:05Z T404a MERGE CHECK_GREEN worker; 1 attempt (med; ruff-format touch in jobs/dict_refresh.py)
2026-11-13T03:29:15Z D-1 MERGE CHECK_GREEN deploy; 1 attempt (HTTPRoute toggle, worker mem 1Gi/3Gi)
2026-11-13T03:29:44Z F-11 MERGE CHECK_GREEN server; 2 attempts (heap 2g, cached spec, derived counts)
2026-11-13T03:33:40Z W-5a MERGE CHECK_GREEN server; 1 attempt (URGENT: blobs wired, dict refresh/release)
2026-11-13T03:37:07Z T404c MERGE CHECK_GREEN server; 3 check runs (OOM was F-11), 1 attempt
2026-11-13T03:37:54Z T412+T413 MERGE CHECK_GREEN web; 1 attempt (human-reported nav pages)
2026-11-13T03:37:58Z F-12+F-13 MERGE CHECK_GREEN worker; 1 attempt (resume builds pkg; ADR-020 optional pins)
2026-11-13T03:41:22Z C-12 HANDOFF DONE; REVIEW_CHANGES architect (1 major: override katakana reading → /parse 503)
2026-11-13T03:42:56Z T405b MERGE CHECK_GREEN web; 1 attempt (low)
2026-11-13T03:43:31Z C-12 REVIEW_CHANGES reviewer (+readiness-probe major, sync-path override validation); parked for attempt 2
2026-11-13T03:43:46Z T411b CHECK_GREEN server; MERGE 1 attempt (med; not wired until W-5)
2026-11-13T03:48:29Z F-10 MERGE CHECK_GREEN acceptance; 1 attempt (tmpfs, no volume leak)
2026-11-13T03:48:41Z - HANDOVER run 4: 17 merged; T411c awaiting review, C-12 attempt 2 queued; nothing in flight
2026-11-13T03:49:28Z - RESUME run 5; T411c rebased, check running; C-12 DISPATCH architect(opus) attempt 2
2026-11-13T03:52:52Z T411c CHECK_GREEN server after rebase; reviewer + architect dispatched
2026-11-13T03:53:33Z T411c REVIEW_APPROVE architect (2 minors → FOLLOWUPS)
2026-11-13T03:53:51Z T411c MERGE reviewer + architect APPROVE (minors → FOLLOWUPS); 1 attempt
2026-11-13T04:00:12Z C-12 HANDOFF attempt 2 DONE; CHECK_GREEN contracts; reviewer + architect re-check dispatched
2026-11-13T04:00:34Z C-12 REVIEW_APPROVE reviewer (both majors fixed; 1 minor → FOLLOWUPS)
2026-11-13T04:00:50Z C-12 MERGE reviewer + architect APPROVE; 2 attempts
2026-11-13T04:01:08Z T403b DISPATCH writer-senior; T403c+T408a DISPATCH writer (batched server); T401b DISPATCH writer
2026-11-13T04:07:15Z F-14/F-15/F-16 NEW human-reported live 0.1.1 bugs (blob PUT MD5, unregistered modules 501, health version); queued ahead of P4
2026-11-13T04:07:56Z T401b MERGE CHECK_GREEN worker (443); 1 attempt (med). F-14 DISPATCH writer; F-15+F-16 DISPATCH writer
2026-11-13T04:08:21Z F-17/F-18 NEW human-reported (listEntryExamples 501 stub; web dict page hides entry when examples fail)
2026-11-13T04:08:33Z F-18 DISPATCH writer-light (web); F-17 queued for next server slot (≤3 gradle-heavy)
2026-11-13T04:14:32Z - RESUME run 6 after machine crash; MAX_PARALLEL_WRITERS=3, 1 Gradle-heavy at a time
2026-11-13T04:14:32Z F-18 HANDOFF DONE (found after crash); rebased, check web running
2026-11-13T04:14:32Z F-14 DISPATCH writer (resume in crashed worktree, uncommitted work kept)
2026-11-13T04:14:32Z T403b DISPATCH writer-senior (resume in crashed worktree)
2026-11-13T04:14:57Z F-18 CHECK_GREEN web; MERGE 1 attempt (low)
2026-11-13T04:19:49Z T403b HANDOFF DONE (519 worker tests, agreement 0.978); check worker + reviewer dispatched
2026-11-13T04:20:40Z F-14 MERGE CHECK_GREEN server; 1 attempt (URGENT, med)
2026-11-13T04:20:47Z F-15+F-16 DISPATCH writer (resume in crashed worktree); T403b CHECK_GREEN worker
2026-11-13T04:20:56Z T403b MERGE REVIEW_APPROVE (4 minors → FOLLOWUPS); CHECK_GREEN worker; 1 attempt (high)
2026-11-13T04:21:08Z T404b DISPATCH writer-senior (worker, no gradle)
2026-11-13T04:25:03Z F-19 NEW: image build never passes version → /health 'dev' (gap found auditing F-16)
2026-11-13T04:25:17Z F-15+F-16 MERGE CHECK_GREEN server; 1 attempt (URGENT, med)
2026-11-13T04:25:25Z F-17 DISPATCH writer (gradle slot); F-19 DISPATCH writer-light
2026-11-13T04:25:52Z F-19 MERGE CHECK_GREEN deploy; 1 attempt (low)
2026-11-13T04:30:09Z F-17 MERGE CHECK_GREEN server; 1 attempt (low)
2026-11-13T04:30:19Z T403c+T408a DISPATCH writer (resume crashed wt, Gradle slot)
2026-11-13T04:34:08Z T403c+T408a MERGE CHECK_GREEN server; 1 attempt (low+med)
2026-11-13T04:34:27Z T406+T408b DISPATCH writer (web batch); T410 DISPATCH writer (android, Gradle slot)
2026-11-13T04:35:38Z T404b MERGE CHECK_GREEN worker; 1 attempt (med)
2026-11-13T04:39:09Z T406+T408b MERGE CHECK_GREEN web; 1 attempt (med+low)
2026-11-13T04:39:47Z T410 MERGE CHECK_GREEN android; 1 attempt (low)
2026-11-13T04:40:01Z - HANDOVER run 6: 10 merged (post-crash recovery); 0.1.2 ready; W-5 next; nothing in flight
2026-11-13T04:41:11Z - RESUME run 7; clean state, 16 GB free
2026-11-13T04:41:11Z W-5 DISPATCH writer (server Gradle slot)
2026-11-13T04:41:11Z T604 DISPATCH writer-light (cli, no gradle; ticket created, deps T110 only)
2026-11-13T04:42:03Z T604 DECIDE media import: no deck name in API → --no-deck/--new-per-day, --kind default other, title=file stem; resumed
2026-11-13T04:46:10Z T604 MERGE CHECK_GREEN cli (30 tests); 1 attempt + 1 decision (low)
2026-11-13T04:49:22Z W-5 MERGE CHECK_GREEN server+worker+deploy; 1 attempt (med); deploy changes → HUMAN_CHECKS
2026-11-13T04:55:43Z - make test GREEN all modules (incl. acceptance, cli); 13 GB free
2026-11-13T04:55:43Z GATE-P4 GATE_CLOSE deferred → HUMAN_CHECKS; phase P5
2026-11-13T04:55:43Z T505 SPLIT T505a (fixtures, haiku) + T505b (server CRUD, deps T505a)
2026-11-13T04:56:05Z T501 DISPATCH writer (core+server, Gradle slot)
2026-11-13T04:56:05Z T505a DISPATCH writer-light (fixtures)
2026-11-13T04:58:40Z T505a MERGE validate.py green (280 stubs); 1 attempt (low)
2026-11-13T05:07:33Z T501 MERGE CHECK_GREEN core+server; 1 attempt (med)
2026-11-13T05:08:19Z T505b DISPATCH writer (server, Gradle slot)
2026-11-13T05:16:41Z T505b MERGE CHECK_GREEN server; 1 attempt (low); minors → FOLLOWUPS F-20
2026-11-13T05:17:21Z T506 SPLIT T506a (worker DSL/matcher/tagging, opus high) + T506b (server rule tester/examples/spans); DSL spec lives in worker (single impl), no contract
2026-11-13T05:17:27Z T503 DISPATCH writer (core+server, Gradle slot)
2026-11-13T05:17:27Z T506a DISPATCH writer-senior (worker, no gradle)
2026-11-13T05:25:20Z T503 MERGE CHECK_GREEN core+server; 1 attempt (med); minor → F-21
2026-11-13T05:25:20Z T502 DISPATCH writer (worker+server, Gradle slot)
2026-11-13T05:35:30Z T502 MERGE CHECK_GREEN server; 1 attempt (low); suggestions server-side (no worker job)
2026-11-13T05:35:30Z T506a HANDOFF CONTRACT_CHANGE_REQUEST (grammar-detect JobKind); BLOCKED_CONTRACT; C-13 opened; reviewer on code meanwhile
2026-11-13T05:37:09Z T506a REVIEW_APPROVE 3 minors → F-22; awaiting C-13 for registry line
2026-11-13T05:41:53Z C-13 REVIEW_CHANGES major: fan-out key release races op-apply enqueue (lost rule edit); attempt 2 to architect
2026-11-13T05:45:53Z C-13 MERGE REVIEW_APPROVE (re-review) CHECK_GREEN contracts; 2 attempts
2026-11-13T05:45:53Z T506a DISPATCH writer-senior attempt 2 (resume: registry line, C-13 key/snapshot rules, detection in parse/import publishes)
2026-11-13T05:45:53Z W-6 DISPATCH writer (server, Gradle slot)
2026-11-13T05:55:49Z W-6 MERGE CHECK_GREEN server; 1 attempt (med); DONE-partial: testGrammarRules 501 → C-14 + T506c; out-of-path JobRepo/NtfySink edits accepted
2026-11-13T05:55:49Z - DISK 8.7 GB free (<10 GB): no further builds this run
2026-11-13T05:56:17Z T506a MERGE REVIEW_APPROVE (re-review, 1 minor → F-23) CHECK_GREEN worker (745); 2 attempts (high)
2026-11-13T05:56:17Z - HANDOVER run 7: 10 merged; P4 gate deferred; disk 8.7 GB (<10) → stopped; next C-14/T506c, T507, T504, T508
2026-11-13T05:57:53Z - RESUME run 8: clean state; disk 19 GB; 195 docker volumes
2026-11-13T05:58:36Z C-14 DISPATCH architect (contracts)
2026-11-13T05:58:36Z D-2 DISPATCH writer (volume leak; Gradle slot)
2026-11-13T05:58:36Z T504 SPLIT T504a web, T504b android+widget
2026-11-13T05:58:36Z T504a DISPATCH writer (web)
2026-11-13T06:01:54Z T504a MERGE CHECK_GREEN web; 1 attempt (low); hot-word extra drill = all hot words (F-21)
2026-11-13T06:03:04Z C-14 HANDOFF DONE contracts green; reviewer dispatched
2026-11-13T06:04:43Z C-14 MERGE REVIEW_APPROVE CHECK_GREEN contracts; 1 attempt; 4 minors → F-24
2026-11-13T06:05:42Z D-2 MERGE CHECK_GREEN core+server; volumes stable at 195; prune cmd → HUMAN_CHECKS
2026-11-13T06:05:42Z T506c DISPATCH writer (worker+server, Gradle slot)
2026-11-13T06:20:55Z T506c MERGE CHECK_GREEN worker+server; 1 attempt (med); create/update w/ rules now need PARSER_URL (503 otherwise)
2026-11-13T06:20:55Z D-3 DISPATCH writer (volumes 195→216 after D-2; remaining leak source)
2026-11-13T06:26:48Z D-3 MERGE CHECK_GREEN worker; 1 attempt (low); worker pg_server fixture tmpfs; volumes stable
2026-11-13T06:26:48Z T507 DISPATCH writer (core+server, Gradle slot; F-20 folded in)
2026-11-13T06:35:45Z T607 DECIDE DEFERRED by human (LLM assist); P6 complete without it
2026-11-13T06:38:26Z T507 MERGE CHECK_GREEN core+server; 1 attempt (med); sentence source unexposed → F-25
2026-11-13T06:38:26Z - HANDOVER run 8: 6 merged; context budget; next F-25, T504b, T508
2026-11-13T06:39:27Z - RESUME run 9: clean state; disk 32 GB free
2026-11-13T06:39:27Z T508 SPLIT T508a web, T508b android
2026-11-13T06:39:27Z C-15 DISPATCH architect (contracts; for F-25 grammar card sentence op)
2026-11-13T06:39:27Z T508a DISPATCH writer (web)
2026-11-13T06:42:26Z T508a MERGE CHECK_GREEN web; 1 attempt (low)
2026-11-13T06:48:14Z C-15 HANDOFF DONE contracts green; QueueItem.grammar_sentence (additive); reviewer dispatched
2026-11-13T06:48:14Z T504b DISPATCH writer (android, Gradle slot)
2026-11-13T06:48:50Z C-15 MERGE REVIEW_APPROVE (3 minors → F-25) contracts green; 1 attempt (high)
2026-11-13T06:49:05Z F-25 DISPATCH writer (core+server+web; Gradle after T504b)
2026-11-13T06:53:43Z T504b MERGE CHECK_GREEN android; 1 attempt (low); online-only writes → F-27
2026-11-13T06:53:58Z T508b DISPATCH writer (android; Gradle after F-25 checks)
2026-11-13T07:07:52Z F-25 MERGE CHECK_GREEN core+server+web; 1 attempt (med); picker steps past lastShown (full coverage)
2026-11-13T07:07:52Z F-25b NEW android half of F-25 (after T508b)
2026-11-13T07:08:10Z F-22 DISPATCH writer (worker; items 2-3)
2026-11-13T07:13:00Z T508b MERGE CHECK_GREEN android; 1 attempt (low); online-only, no dict-example hook (no such view)
2026-11-13T07:13:00Z F-22 MERGE CHECK_GREEN worker (759); items 2-3 already done, tests added; T508 complete
2026-11-13T07:13:13Z F-25b DISPATCH writer (android, Gradle slot)
2026-11-13T07:16:47Z F-25b MERGE CHECK_GREEN android; 1 attempt (med); P5 tickets all merged → make test
2026-11-13T07:24:08Z - MAKE_TEST GREEN (all modules) after P5
2026-11-13T07:24:08Z GATE-P5 GATE_OPEN deferred → HUMAN_CHECKS
2026-11-13T07:24:08Z - HANDOVER run 9: 7 merged; context budget; P5 done; next: optional followups F-27/F-24/F-23/F-21, finish
2026-11-13T07:24:55Z - RESUME run 10: STATE corrected (P6 has 6 TODO + T609/T610); T604 marked MERGED in BACKLOG; disk 32 GB
2026-11-13T07:29:01Z C-16 NEW contract for T606: internal job kind so server mints fsrs_params.published from optimizer result
2026-11-13T07:29:01Z T601 DISPATCH writer (worker+server+deploy)
2026-11-13T07:29:01Z T602 DISPATCH writer (core)
2026-11-13T07:29:01Z C-16 DISPATCH architect (contracts)
2026-11-13T07:35:27Z T602 MERGE CHECK_GREEN core+web; 1 attempt (low); DefaultCardRenderer 3-line + renderer test edits outside paths accepted
2026-11-13T07:35:42Z T603 DISPATCH writer (server, med)
2026-11-13T07:44:47Z C-16 MERGE CHECK_GREEN contracts; 1 attempt; additive only, no DDL
2026-11-13T07:45:16Z T605 DISPATCH writer (server+web, low)
2026-11-13T07:50:30Z T608 DISPATCH writer (server, med); T603 REVIEW dispatched
2026-11-13T07:51:37Z T603 REVIEW APPROVE (8 minors -> FOLLOWUPS); full server check pending after T601 merge
2026-11-13T07:52:21Z T601 MERGE CHECK_GREEN worker+server (deploy per writer); wiring-audit test edits outside paths accepted
2026-11-13T07:52:35Z T606 DISPATCH writer (worker+server, med)
2026-11-13T07:55:51Z T603 MERGE CHECK_GREEN server (full, post-rebase on T601); review APPROVE
2026-11-13T08:10:33Z T608 REVIEW APPROVE (minors -> FOLLOWUPS); merge after T605
2026-11-13T08:16:55Z T606 REVIEW APPROVE (minors -> FOLLOWUPS); torch image size flagged
2026-11-13T08:18:44Z T605 MERGE CHECK_GREEN server+web after rebase (Main.kt/WiringAudit conflicts resolved by orchestrator)
2026-11-13T08:22:07Z T608 MERGE CHECK_GREEN server after rebase (WiringAudit conflict resolved by orchestrator); new dep sqlite-jdbc 3.47.1.0
2026-11-13T08:26:27Z T606 MERGE CHECK_GREEN worker+server after rebase; review APPROVE. RUN 10 END: 7 merges, stopped for context
2026-11-13T08:27:23Z RUN11 START main 5eca45d; disk /home 41G free
2026-11-13T08:27:23Z F-28 DISPATCH sonnet writer (filed from T606 torch HUMAN_CHECK)
2026-11-13T08:27:23Z T609 DISPATCH writer-senior (opus/high)
2026-11-13T08:34:28Z F-28 MERGE CHECK_GREEN worker; CPU torch, image 2.04GB
2026-11-13T08:53:50Z T609 HANDOFF DONE; CHECK_GREEN deploy; review dispatched. NOTE kubectl ctx left at 'homepage' after writer's k3d run
2026-11-13T08:56:39Z T609 REVIEW_APPROVE (5 minors -> FOLLOWUPS)
2026-11-13T08:56:39Z T609 MERGE CHECK_GREEN deploy
2026-11-13T08:56:39Z SEC-1 DISPATCH writer-senior (S-01 HIGH)
2026-11-13T08:56:39Z SEC-2 DISPATCH writer (S-02)
2026-11-13T08:56:39Z T610 DISPATCH writer-light
2026-11-13T09:00:30Z T610 MERGE docs only (no check per ticket); gaps -> FOLLOWUPS
2026-11-13T09:04:18Z SEC-2 MERGE CHECK_GREEN worker (rebased onto T610)
2026-11-13T09:06:23Z SEC-1 REVIEW_APPROVE (3 minors -> FOLLOWUPS); MERGE CHECK_GREEN server after rebase
2026-11-13T09:15:21Z P6 MAKE_TEST green; RUN 11 END: 5 merges (F-28,T609,T610,SEC-2,SEC-1); P6 complete -> GATE-DONE
2026-11-13T09:33:21Z RUN12 START main d1da50a; disk /home 39G free; bugfix run (0.1.4 live bugs) BUG-1..BUG-5 filed
2026-11-13T09:33:21Z BUG-1 DISPATCH writer-senior (stats perf, high)
2026-11-13T09:33:21Z BUG-2+BUG-3 DISPATCH writer (worker batch, med)
2026-11-13T09:33:21Z BUG-4 DISPATCH writer (deploy voicevox, low)
2026-11-13T09:36:55Z BUG-6 FILED (coordinator update: jpdb Hard never graduates learning); BUG-1/2/4 detail relayed to writers; live main d618d61 chart a0bcd03
2026-11-13T09:41:05Z BUG-4 MERGE CHECK_GREEN deploy (low, no review); smoke uid1000 RO rootfs /version 0.21.1
2026-11-13T09:41:05Z BUG-6 DISPATCH architect (opus, high)
2026-11-13T09:45:31Z BUG-2+BUG-3 MERGE CHECK_GREEN worker+web (med, no review); web messages.ts edit outside paths accepted
2026-11-13T09:45:31Z BUG-5 DISPATCH writer; HANDOFF DONE; 501 allowlist empty
2026-11-13T09:45:43Z BUG-5 MERGE CHECK_GREEN server (med, no review)
2026-11-13T09:56:38Z BUG-1 HANDOFF BLOCKED (run 12): stats endpoints <1s except summary; asks for QueueLoader.kt (same LAG rewrite)
2026-11-13T09:56:38Z BUG-6 HANDOFF BLOCKED (run 12): contracts+core green; asks for server CoreCardCache + import_source selects
2026-11-13T09:56:38Z RUN12 END: died mid-run (API auth error), no handover; merges BUG-4, BUG-2+BUG-3, BUG-5 (main a0e509e)
2026-11-13T09:56:38Z RUN13 START main a0e509e; disk /home 38G free; run-12 BUG-6 architect still alive in its worktree
2026-11-13T09:56:38Z BUG-1 DECIDE coordinator: option (a), owned paths += server/.../review/QueueLoader.kt
2026-11-13T09:56:38Z BUG-6 DECIDE coordinator: grant CoreCardCache.kt + import_source in StatsQueries/StruggleModule/DrillSources + server test; merge after BUG-1
2026-11-13T09:56:38Z BUG-1 DISPATCH writer-senior attempt 2 (same worktree)
2026-11-13T09:58:29Z BUG-6 DECIDE ADR-021 PROVISIONAL jpdb import graduation (fsrs6-2); HUMAN_CHECKS line
2026-11-13T09:58:34Z BUG-6 DISPATCH architect (opus) attempt 2: server import_source + replay equality test
2026-11-13T10:07:37Z BUG-1 HANDOFF DONE: summary 153ms (was 19.7s), queue 48ms, all stats <600ms @90k/10k
2026-11-13T10:07:37Z BUG-1 REVIEW dispatched reviewer (high)
2026-11-13T10:08:45Z BUG-1 REVIEW_APPROVE (3 minors -> FOLLOWUPS); rebased onto 914e2a4, re-checking server+web
2026-11-13T10:10:00Z BUG-6 HANDOFF DONE 8f52b5f: server import_source + ImportReplayServerTest; contracts/core/server green
2026-11-13T10:10:00Z BUG-6 REVIEW dispatched reviewer + architect second pass
2026-11-13T10:10:47Z BUG-6 REVIEW_APPROVE architect (1 note -> FOLLOWUPS)
2026-11-13T10:11:13Z BUG-6 REVIEW_APPROVE reviewer (2 minors; day-boundary note added to ADR-021)
2026-11-13T10:11:27Z BUG-1 NOTE coordinator: web queue slow -> covered; GET /review/queue load+build 48/37ms @89.8k/10k (StatsPerfTest.reviewQueueLoadsWithinOneSecond)
2026-11-13T10:12:54Z BUG-1 MERGE e9b8350 CHECK_GREEN server+web after rebase (review APPROVE)
2026-11-13T10:12:54Z BUG-6 REBASE conflict StatsQueries (1 hunk) -> writer resolving
2026-11-13T10:27:49Z RUN13 END: died mid-run (API auth error), no handover; merged BUG-1 (e9b8350, unpushed)
2026-11-13T10:27:49Z RUN14 START main e9b8350; committed run 13 board state (59985bc)
2026-11-13T10:27:49Z BUG-6 REBASE onto 59985bc clean; CHECK_GREEN core+server+contracts
2026-11-13T10:27:49Z BUG-6 MERGE a660c52 (review APPROVE reviewer+architect); worktree+branches removed
2026-11-13T10:34:40Z RUN14 END make test rc=0 at a660c52; P7 complete; P8 (C-17, FEAT-1) left TODO pending user confirm
2026-11-13T10:36:46Z RUN15 START main 60c8391; disk /home 37G free; P8 approved by user
2026-11-13T10:36:46Z C-17 DISPATCH architect (opus, med)
2026-11-13T10:38:30Z AND-SEARCH,AND-DECKS,AND-SETTINGS FILED (tickets); UI-1 HUMAN-GATED entry
2026-11-13T10:38:30Z AND-SEARCH DISPATCH writer (sonnet, med)
2026-11-13T10:38:30Z AND-DECKS DISPATCH writer (sonnet, med)
2026-11-13T10:42:34Z C-17 MERGE CHECK_GREEN contracts (med, no review); listKanjiWords + clients regen
2026-11-13T10:42:34Z FEAT-1 DISPATCH writer (sonnet, med)
2026-11-13T10:43:27Z AND-SETTINGS DECIDE coordinator: render all FeatureEntry.settingsSections (QR pairing reachable), add only limits/day boundary/about; unpaired first-launch route
2026-11-13T10:45:22Z AND-DECKS HANDOFF DONE 908589e; AppStartsTest stopKoin edit outside paths accepted (test fix)
2026-11-13T10:45:22Z AND-SETTINGS DISPATCH writer (sonnet, med; revised ticket)
2026-11-13T10:49:00Z AND-DECKS MERGE CHECK_GREEN android (med, no review); read-only tab, deck study via ReviewScreen deckId
2026-11-13T10:49:13Z AND-DECKS NOTE first ff failed (main moved), branch restored from 908589e, rebased, merged
2026-11-13T10:54:03Z FEAT-1 MERGE CHECK_GREEN server+web (med, no review); web lib/dictionary api/messages edits
2026-11-13T10:54:29Z AND-SEARCH HANDOFF DONE af01458; REBASE conflict FeatureModules/AppStartsTest -> writer resolving (attempt 2)
2026-11-13T10:56:46Z AND-SEARCH MERGE CHECK_GREEN android (med, no review; attempt 2 = rebase fix)
2026-11-13T10:57:01Z AND-SETTINGS HANDOFF DONE; KotobayaApp open + Home hook accepted; REBASE conflict -> writer resolving (attempt 2)
2026-11-13T10:58:53Z AND-SETTINGS MERGE CHECK_GREEN android (med, no review; attempt 2 = rebase fix); SettingsTabTest covers Devices/Scan QR
2026-11-13T10:59:05Z AND-SETTINGS NOTE first ff failed (main moved), restored from 7b264e0, rebased (plan-only delta), merged
2026-11-13T11:04:29Z RUN15 END make test rc=0; P8 complete (5 merges); UI-1 HUMAN-GATED
2026-11-13T11:05:33Z BUG-7 DISPATCH writer (sonnet, low); card browser preview for dictionary-backed notes
2026-11-13T11:07:58Z BUG-7 HANDOFF DONE c81147d; CONTRACT_CHANGE_REQUEST batch headwords (shipped id fallback)
2026-11-13T11:07:58Z BUG-7 MERGE CHECK_GREEN web (low, no review); row click + Enter, rowLabel fallback, dict-backed preview
2026-11-13T11:07:58Z C-18 SPLIT filed from BUG-7 CCR (batch headwords contract); BUG-7b filed (server+web, dep C-18)
2026-11-13T11:08:06Z C-18 DISPATCH architect (opus, med)
2026-11-13T11:08:50Z BUG-7 NOTE user addendum (/notes) arrived after merge -> BUG-7b; batch wiring renamed BUG-7c (deps C-18, BUG-7b); C-18 widened to headword/reading/gloss
2026-11-13T11:08:50Z BUG-7b DISPATCH writer (sonnet, low; web notes)
2026-11-13T11:10:54Z BUG-7b NOTE BUG-7 addendum 2 (kanji preview, kind switcher, /review check) sent to running BUG-7b writer; paths widened (cards/review)
2026-11-13T11:10:54Z FEAT-x NOTE filed HUMAN-GATED next to UI-1 (WYSIWYG note view; needs core render-contract C-xx first)
2026-11-13T11:12:40Z BUG-7b HANDOFF DONE; addendum 1+2 covered; /review kanji already correct (no change)
2026-11-13T11:12:40Z BUG-7b MERGE CHECK_GREEN web (low, no review); notes Preview|Edit, kind switcher both pages, kanji fetch, dict field values
2026-11-13T11:19:46Z C-18 HANDOFF DONE; listDictHeadwords GET /dict/headwords?seq= (<=500), 501 stub allowlisted for BUG-7c
2026-11-13T11:19:46Z C-18 MERGE CHECK_GREEN contracts+server (med, additive, no review); clients regen
2026-11-13T11:19:46Z BUG-7c DISPATCH writer (sonnet, low; server+web batch headwords)
2026-11-13T11:28:47Z BUG-7c HANDOFF DONE; listDictHeadwords server + Netty initial line 16384; both tables batch-filled
2026-11-13T11:28:47Z BUG-7c MERGE CHECK_GREEN server+web (low, no review); 501 allowlist empty again
2026-11-13T11:34:21Z RUN16 END make test rc=0; 4 merges (BUG-7, BUG-7b, C-18, BUG-7c); FEAT-x HUMAN-GATED
2026-11-13T12:14:45Z RUN17 START main 6c954bd; disk /home 34G free; INBOX run 17 brief (UI-1/FEAT-x ungated)
2026-11-13T12:14:45Z HOTFIX-0.1.7,BUG-8,BUG-9,BUG-10 FILED Phase 7 from INBOX I-1..I-4,I-7
2026-11-13T12:14:45Z UI-FOUND SPLIT UI-FOUND-WEB / UI-FOUND-APP (app half dep BUG-8: same android IO code)
2026-11-13T12:14:45Z UI-DS,C-19,UI-WEB,UI-APP FILED Phase 9; UI-WEB/UI-APP HUMAN-GATED (B2 gate); INBOX archived plan/handoffs/INBOX-0.1.6.md
2026-11-13T12:15:03Z HOTFIX-0.1.7 DISPATCH writer-light (haiku, low)
2026-11-13T12:15:03Z C-19 DISPATCH architect (opus, med, additive)
2026-11-13T12:18:03Z HOTFIX-0.1.7 HANDOFF BLOCKED-ish: fix done, vitest needs jsdom (dep add denied by classifier) -> FOLLOWUPS + HUMAN_CHECKS
2026-11-13T12:18:03Z HOTFIX-0.1.7 MERGE a5c4e3a CHECK_GREEN web+deploy (low, no review); ship as 0.1.7
2026-11-13T12:18:23Z BUG-8 DISPATCH writer-senior (opus, high)
2026-11-13T12:18:23Z BUG-9 DISPATCH writer (sonnet, med)
2026-11-13T12:23:30Z TEST-1 FILED human approved jsdom devDep 2026-11-13 (via coordinator); HUMAN_CHECKS question cleared
2026-11-13T12:25:27Z BUG-9 HANDOFF DONE 9fe3e5e; stats 7-607ms on 89,775 reviews, peak heap 335MB; no -Xmx256m run
2026-11-13T12:25:27Z TEST-1 DISPATCH writer-light (haiku, low; jsdom human-approved)
2026-11-13T12:25:56Z BUG-9 MERGE ac0765f CHECK_GREEN server (med, no review); for 0.1.8
2026-11-13T12:26:16Z BUG-10 DISPATCH writer (sonnet, med)
2026-11-13T12:26:16Z C-19 HANDOFF DONE 617299d; core+contracts+web green per writer
2026-11-13T12:29:07Z C-19 MERGE 578985c CHECK_GREEN contracts+core+web (med additive, no review)
2026-11-13T12:30:21Z TEST-1 MERGE b43dbca CHECK_GREEN web (low, no review); jsdom@31.0.0
2026-11-13T12:30:21Z UI-FOUND-WEB DISPATCH writer-senior (opus, med)
2026-11-13T12:37:30Z BUG-10 HANDOFF DONE; hot words 7221->110ms warm, hot kanji ~7.7s->94ms; kanji words not reproduced
2026-11-13T12:40:54Z BUG-10 MERGE 4ce6632 CHECK_GREEN server (med, no review); for 0.1.8
2026-11-13T12:44:28Z UI-DS SPLIT human direction: UI-DS deps UI-FOUND-WEB only (theme files only on android); UI-DS-APP filed (deps UI-DS, UI-FOUND-APP; not gated); gate at UI-DS merge
2026-11-13T13:06:20Z BUG-8 HANDOFF DONE; 100k pull 55.8s bench, 200-op tx chunks, WAL reader, StrictMode+DbThreadGuard
2026-11-13T13:06:20Z BUG-8 REVIEW dispatched reviewer + architect (high)
2026-11-13T13:09:34Z BUG-8 REVIEW_APPROVE reviewer (6 minors: 2 -> FOLLOWUPS, 3 folded into UI-FOUND-APP)
2026-11-13T13:11:11Z BUG-8 REVIEW_APPROVE architect (a/b/c signed off; minors -> SYNC-T1 + C-20 filed)
2026-11-13T13:16:49Z BUG-8 MERGE dcc3216 CHECK_GREEN core+android+acceptance+server (high; reviewer + architect APPROVE)
2026-11-13T13:17:25Z UI-FOUND-WEB MERGE 2056faa CHECK_GREEN web (med, no review); 672 screenshots
2026-11-13T13:17:25Z UI-DS DISPATCH writer-senior (opus, med; gate on merge)
2026-11-13T13:17:25Z UI-FOUND-APP DISPATCH writer-senior (opus, med)
2026-11-13T13:55:45Z UI-DS MERGE 3007499 CHECK_GREEN web+core+android (med, no review)
2026-11-13T13:55:45Z B2 GATE_OPEN UI-WEB/UI-APP HUMAN-GATED; HUMAN_CHECKS top READY TO READ
2026-11-13T13:55:45Z RUN17 END 8 merges; UI-FOUND-APP in flight (t/UI-FOUND-APP-viewmodels-harness)
2026-11-13T13:57:09Z RUN18 START resume; UI-FOUND-APP writer (run 17) still alive, not touched
2026-11-13T13:57:09Z SYNC-T1 DISPATCH writer-senior (opus, med)
2026-11-13T13:57:09Z C-20 DISPATCH architect (opus, low; wording only)
2026-11-13T13:59:52Z UI-FOUND-APP HANDOFF DONE (writer finished, reported to lifecycle session); 516 screenshots
2026-11-13T13:59:52Z UI-FOUND-APP DECIDE orchestrator resolved trivial Makefile .PHONY rebase conflict (union with tokens targets)
2026-11-13T13:59:52Z UI-FOUND-APP MERGE b0683c1 CHECK_GREEN android after rebase (med, no review)
2026-11-13T13:59:52Z UI-DS-APP DISPATCH writer-senior (opus, low)
2026-11-13T15:44:26Z C-20 HANDOFF DONE; wording 6.1 sub-pages + 9.3 deferral, xrefs
2026-11-13T15:44:26Z C-20 MERGE 6814485 CHECK_GREEN contracts (low, no review)
2026-11-13T15:44:26Z B2 GATE_CLOSE human approved DESIGN.md + styleguide (2026-11-14); UI-WEB, UI-APP -> TODO
2026-11-13T15:44:26Z SYNC-T1 BLOCK attempt 1 died on API 429 (uncommitted work in worktree); TODO, attempt kept
2026-11-13T15:44:26Z UI-DS-APP BLOCK attempt 1 died on API 429 (no work saved); TODO, attempt kept
2026-11-13T15:44:26Z RUN18 END 2 merges; context budget; WORK_REMAINS
2026-11-13T15:45:43Z RUN19 START resume; removed merged UI-FOUND-APP worktree + branch
2026-11-13T15:45:43Z SYNC-T1 DISPATCH writer-senior attempt 2 (resume partial worktree)
2026-11-13T15:45:43Z UI-DS-APP DISPATCH writer-senior attempt 2 (fresh)
2026-11-13T15:45:43Z UI-WEB DISPATCH writer-senior attempt 1 (high; bar + screenshot matrix verbatim)
2026-11-13T15:48:57Z SYNC-T1 MERGE 4dce8ba CHECK_GREEN core (med, no review); 7 named chunk tests + fuzz chunk 1..5
2026-11-13T16:06:48Z UI-DS-APP MERGE 92889d0 CHECK_GREEN android (low, no review)
2026-11-13T16:06:48Z UI-APP DISPATCH writer-senior attempt 1 (high; bar + screenshot matrix verbatim)
2026-11-13T16:43:30Z UI-WEB HANDOFF CONTRACT_CHANGE_REQUEST (additive state: PreviewInput.fieldRegions); rest DONE, 688 PNGs
2026-11-13T16:43:30Z UI-WEB DECIDE scope amended: + web/src/lib/state/{preview,notes,cards}.svelte.ts fieldRegions, load-error flags, dead lib/<feature>/*.svelte + legacy tokens + Toaster removal, app.html pre-paint; fresh writer-senior attempt 1b same branch
2026-11-13T16:48:48Z UI-APP HANDOFF DONE; 660 PNGs, 176 curated; glue edits outside ui/nav accepted (theme switch, Media route removal); state-layer requests -> APP-STATE-1
2026-11-13T16:48:48Z UI-APP REVIEW dispatched reviewer (screenshots + bar)
2026-11-13T16:51:45Z UI-APP REVIEW_CHANGES 2 majors (grammar capture unreachable; system bars ignore in-app theme) + 6 minors
2026-11-13T16:51:45Z UI-APP DISPATCH writer-senior attempt 2 (majors + minors 6,8; same worktree)
2026-11-13T16:55:19Z UI-WEB HANDOFF DONE (amendment round); token generator edit accepted (legacy block moved)
2026-11-13T16:55:19Z UI-WEB REVIEW dispatched reviewer (screenshots + bar)
2026-11-13T16:58:01Z UI-WEB REVIEW_CHANGES 4 majors (200% answer below fold; table cols misaligned; segmented clip 360; FEAT-x click creates override) + 5 minors
2026-11-13T16:58:01Z UI-WEB DISPATCH writer-senior attempt 2 (majors + minors 5,7,8,9; same worktree)
2026-11-13T16:59:59Z UI-APP REVIEW_APPROVE round 2 (major #1 withdrawn: Struggle capture reachable)
2026-11-13T16:59:59Z UI-APP MERGE 2587826 CHECK_GREEN android (high; reviewer APPROVE)
2026-11-13T17:11:18Z UI-WEB REVIEW_CHANGES round 2: 4 majors verified fixed; 1 new major (Switch label clip 360@200%)
2026-11-13T17:11:18Z UI-WEB DISPATCH writer attempt 3 (Switch label wrap; same worktree)
2026-11-13T17:16:03Z UI-WEB REVIEW_APPROVE round 3
2026-11-13T17:16:03Z UI-WEB MERGE 8095cce CHECK_GREEN web (high; reviewer APPROVE)
2026-11-13T17:16:03Z RUN19 END 4 merges; P9 done; 0.1.9 ready in HUMAN_CHECKS
2026-11-14T00:06:36Z RUN20 START bugs: jpdb import 0 cards; JLPT decks 0 words (0.1.9 live)
2026-11-14T00:10:01Z BUG-11 DISPATCH writer attempt 1 (jpdb slugless URL -> overview page, 0 rows)
2026-11-14T00:10:01Z C-21 DISPATCH architect attempt 1 (JLPT vocab source contract)
2026-11-14T00:10:16Z RUN20 DECIDE live sweep: no new high bugs; minors -> FOLLOWUPS
2026-11-14T00:19:19Z BUG-11 CHECK_GREEN worker 805 passed; fmt-only grammar test edits accepted; reviewer dispatched
2026-11-14T00:20:16Z BUG-11 REVIEW_APPROVE 5 minors -> FOLLOWUPS
2026-11-14T00:20:16Z BUG-11 MERGE d3711c2 worker
2026-11-14T00:22:44Z UI-RUBY DISPATCH writer-light attempt 1 (user ask: larger furigana)
2026-11-14T00:23:18Z C-21 HANDOFF DONE; CHECK_GREEN contracts; reviewer dispatched (architect authored, second architect pass skipped)
2026-11-14T00:24:07Z BUG-12 DISPATCH writer attempt 1 (struggle Open in dictionary broken)
2026-11-14T00:24:30Z C-21 REVIEW_APPROVE 4 minors -> FOLLOWUPS
2026-11-14T00:24:30Z C-21 MERGE 1578783 contracts (V3__vocab_list, VOCAB_LISTS.md, deck-publish, ADR-022)
2026-11-14T00:25:05Z JLPT-1a DISPATCH writer attempt 1
2026-11-14T00:25:05Z JLPT-1b DISPATCH writer-senior attempt 1
2026-11-14T00:26:02Z BUG-12 MERGE 82a5b27 web (low, no review; root: struggle link to bare /dictionary)
2026-11-14T00:26:49Z UI-RUBY HANDOFF BLOCKED (Android ThemeApiTest pins 20sp); DECIDE apply 0.6 on Android too, scope + test/messages/DESIGN; attempt 2
2026-11-14T00:27:23Z BUG-14 DIAGNOSE opus scout (CRITICAL: review stuck on same card)
2026-11-14T00:27:23Z BUG-13 DISPATCH writer attempt 1 (open note bounce)
2026-11-14T00:27:38Z UI-RUBY DISPATCH writer-light attempt 2 (fresh agent, same worktree; ticket amended)
2026-11-14T00:28:59Z BUG-13 MERGE 860196f web (low; notes start() search() reset dropped deep-linked note)
2026-11-14T00:29:45Z BUG-14 DIAGNOSED: web answerId reuse (dedup as retry) + core queue learn-ahead before due reviews; no data loss (no outbox)
2026-11-14T00:29:45Z BUG-14a DISPATCH writer attempt 1; C-22 DISPATCH architect attempt 1
2026-11-14T00:30:40Z BROWSE-1 BROWSE-2 FILED (user asks D/E; queued behind critical)
2026-11-14T00:31:15Z UI-RUBY MERGE aa8d61d web+android (low; ruby 0.6/12px; reviewer-light skipped, context budget)
2026-11-14T00:31:25Z RUN20 END context budget; RESTART NEEDED; in flight JLPT-1a, JLPT-1b, BUG-14a, C-22
2026-11-14T00:31:41Z BUG-14a HANDOFF DONE 236135e; newPress() fresh id per press; Android unaffected; READY_FOR_REVIEW
2026-11-14T00:32:24Z RUN21 START resume; JLPT-1a/1b + C-22 agents still alive (orphaned, running checks); BUG-14a reviewer dispatched
2026-11-14T00:32:56Z BUG-14a REVIEW_CHANGES round 1 (major: recorded answer + failed refetch leaves card gradeable with fresh id); DISPATCH writer attempt 2 same worktree
2026-11-14T00:34:37Z BUG-14a REVIEW_APPROVE round 2; MERGE 3704bd4 web (CHECK_GREEN 41 tests)
2026-11-14T00:35:15Z RUN21 USER_REPORTS F (android insets → AND-INSETS ticket) G (android post-sync loads fail → DIAGNOSE scout) H (crash; no adb device → HUMAN_CHECKS)
2026-11-14T00:35:17Z JLPT-1a HANDOFF DONE ac0977b worker green; default digests untested offline; READY_FOR_REVIEW
2026-11-14T00:36:21Z RUN21 DECIDE user: no interim 0.1.10; single release after all items. DISPATCH JLPT-1a reviewer, FEAT-DICT writer, BROWSE-1+2 writer (batched). FILED LOGFIX-1 (worker, after JLPT-1a), FEAT-DICT-APP (after AND-INSETS)
2026-11-14T00:36:24Z JLPT-1b HANDOFF DONE server green; READY_FOR_REVIEW (high)
2026-11-14T00:36:38Z G DIAGNOSED partial: server clean; Pixel 98 pulls/23min, no push, no other routes → client-side; FILED AND-POSTSYNC (writer-senior, after C-22)
2026-11-14T00:36:49Z JLPT-1b HANDOFF DONE 069f276 server green; reviewer dispatched (high)
2026-11-14T00:36:59Z AND-INSETS DISPATCH writer attempt 1
2026-11-14T00:37:17Z C-22 HANDOFF DONE core+contracts green; READY_FOR_REVIEW (high)
2026-11-14T00:37:18Z JLPT-1a REVIEW_APPROVE 4 minors (3 → LOGFIX-1, 1 → release checklist: egress raw.githubusercontent.com for JLPT default-on); MERGE 25b203c worker
2026-11-14T00:37:43Z C-22 HANDOFF DONE 1bf8289 core+contracts green; reviewer + architect dispatched. LOGFIX-1 DISPATCH writer attempt 1
2026-11-14T00:37:56Z JLPT-1b REVIEW_APPROVE 3 minors → FOLLOWUPS; MERGE 1927112 server
2026-11-14T00:38:35Z OBS-1 FILED (user request; split C-23, OBS-1s, OBS-1w after LOGFIX-1, OBS-1c after C-23)
2026-11-14T00:38:54Z C-22 REVIEW_APPROVE + ARCHITECT_APPROVE (minors → FOLLOWUPS; ADR wording fixed at merge); MERGE 92cf544 core+contracts
2026-11-14T00:39:32Z BROWSE-1 CONTRACT_CHANGE_REQUEST (entry-linked notes have empty fields) → C-24 FILED; BROWSE-2 DONE web green → reviewer-light
2026-11-14T00:40:00Z RUN21 END context budget; RESTART NEEDED; in flight FEAT-DICT, BROWSE-2 review, C-24, AND-INSETS, AND-POSTSYNC, LOGFIX-1, C-23, OBS-1s
2026-11-14T00:40:18Z BROWSE-2 REVIEW_APPROVE (light, 3 minors → FOLLOWUPS); MERGE a0e16d0 web (post-handback)
2026-11-14T00:41:19Z RUN22 RESUME 7 run-21 agents alive (C-23,C-24,OBS-1s,AND-POSTSYNC,LOGFIX-1,FEAT-DICT,AND-INSETS); polling HANDOFFs
2026-11-14T00:41:19Z RELEASE CHECK worker pod -> raw.githubusercontent.com HTTP 200; JLPT default source stays on. adb: no device
2026-11-14T00:42:23Z FEAT-DICT MERGE 71fbc12 web (low, no review; CHECK_GREEN after rebase on BROWSE-2)
2026-11-14T00:45:14Z LOGFIX-1 REVIEW_APPROVE (ADR-020 amended provisional; 2 minors → OBS-1w); MERGE 03821d4 worker
2026-11-14T00:45:27Z OBS-1w DISPATCH writer attempt 1
2026-11-14T00:45:37Z RUN21 DRAIN context budget: no new dispatches; recording handoffs as READY_FOR_REVIEW until the 6 writers finish
2026-11-14T00:49:27Z C-23 HANDOFF DONE 1bb486d; READY_FOR_REVIEW (drain)
2026-11-14T00:54:36Z OBS-1w HANDOFF DONE worker green 859; READY_FOR_REVIEW (drain)
2026-11-14T00:57:19Z OBS-1s HANDOFF DONE 20418ba server green; READY_FOR_REVIEW (drain)
2026-11-14T01:02:19Z AND-INSETS HANDOFF DONE android green; READY_FOR_REVIEW (drain)
2026-11-14T01:05:00Z RUN23 RESUME C-24 + AND-POSTSYNC writers (run 22) alive finishing; no re-dispatch. Reviews: C-23 reviewer, OBS-1w reviewer, AND-INSETS reviewer-light. OBS-1s DISPATCH writer attempt 2 (t/OBS-1s-c23 = OBS-1s rebased on C-23 + reportClientErrors handler)
2026-11-14T01:06:00Z C-24 HANDOFF DONE 05129a9 core+contracts+server green; reviewer + architect queued. FILED AND-DICTFORMS (android LocalCardSearch dictForms; after C-24)
2026-11-14T01:10:00Z AND-INSETS REVIEW_APPROVE (light; minors: imePadding lifts bottom bar, no device check → HUMAN_CHECKS); rebased, android check running
2026-11-14T01:11:00Z C-23 REVIEW_APPROVE 4 minors (413 for >20 items → OBS-1s attempt 2; 2 → OBS-1c ticket; merge with OBS-1s). C-24 reviewer + architect dispatched
2026-11-14T01:20:00Z OBS-1w REVIEW_APPROVE 6 minors → OBS-1w2; CHECK_GREEN 859; MERGE 9bff9db worker
2026-11-14T01:20:30Z C-24 REVIEW_APPROVE + ARCHITECT_APPROVE (perf minors → AND-DICTFORMS; smart-deck server/device diff → FOLLOWUPS/release notes); MERGE 7ea29c1
2026-11-14T01:21:00Z AND-POSTSYNC HANDOFF DONE 99502d6 android green; reviewer dispatched. OBS-1w2 FILED+DISPATCH writer
2026-11-14T01:25:00Z AND-POSTSYNC REVIEW_APPROVE 3 minors → AND-REVIEWERR (filed, android batch for 0.1.10)
2026-11-14T01:30:00Z AND-INSETS CHECK_GREEN after rebase; MERGE c20cf92 android
2026-11-14T01:30:30Z BROWSE-1 RESOLVED by C-24 (scout: web listCards → server CardSearch Postgres compiler, no client filtering); live-verify after deploy
2026-11-14T01:36:00Z OBS-1w2 HANDOFF DONE; CHECK_GREEN 862; MERGE 2d605df2 worker (low, no review)
2026-11-14T01:40:00Z AND-POSTSYNC CHECK_GREEN after rebase; MERGE f48e2000 android
2026-11-14T02:00:00Z OBS-1s REVIEW_APPROVE (5 minors → OBS-1s2 filed); rebased t/OBS-1s-c23 (DECISIONS ADR-024/025 conflict resolved), contracts+server checks running
2026-11-14T02:20:00Z C-23+OBS-1s CHECK_GREEN contracts+server after rebase (first ff attempt failed: main moved; cherry-picked)
2026-11-14T02:24:00Z C-23+OBS-1s MERGE dc73808e (C-23 + OBS-1s logging + reportClientErrors handler)
2026-11-14T02:26:00Z OBS-1s2 DISPATCH writer attempt 1; OBS-1c SPLIT → OBS-1c-web (DISPATCH writer) + OBS-1c-and (after android batch)
2026-11-14T02:50:00Z AND-REVIEWERR+AND-DICTFORMS+FEAT-DICT-APP HANDOFF DONE android green; REVIEW_APPROVE (minors 1,2,4 + cap test → OBS-1c-and; 5 → FOLLOWUPS); OBS-1c-and DISPATCH writer (from batch tip)
2026-11-14T01:31:47Z WEBVER MERGE 4b1b256e (low; web Dockerfile+Makefile pass KOTOBAYA_VERSION as VITE_APP_VERSION)
2026-11-14T01:31:58Z AND-REVIEWERR+AND-DICTFORMS+FEAT-DICT-APP MERGE d362aed0 android (3 commits; CHECK_GREEN after rebase)
2026-11-14T01:32:59Z OBS-1c-web MERGE 33e1b7d8 web (REVIEW_APPROVE + minors fixed; CHECK_GREEN)
2026-11-14T01:34:34Z OBS-1s2 MERGE 64686a11 server (low; CHECK_GREEN by writer, no server drift on main)
2026-11-14T01:42:54Z OBS-1c-and REVIEW_APPROVE (4 minors → FOLLOWUPS); CHECK_GREEN android; MERGE 6b41f35d (handoff rebase artefact repaired)
2026-11-14T01:45:12Z RELEASE 0.1.10 PIN 0200b2f5 (worktree scratchpad/rel0110); kubectl OIDC token expired (browser login needed) → prep without cluster
2026-11-14T01:50:05Z RELEASE 0.1.10 images FAILED at pin 0200b2f5: WEBVER dropped 'COPY fixtures/demo' from web/Dockerfile (nothing pushed) → WEBVER-fix DISPATCH writer-light; will re-pin
2026-11-14T01:51:59Z WEBVER-fix MERGE db909f66 web Dockerfile (release/0.1.9-only commit c4d8dcb0 never reached main; docker build verified)
2026-11-14T01:52:08Z RELEASE 0.1.10 RE-PIN a58b9699 (rel0110b; code = 0200b2f5 + web/Dockerfile fix)
2026-11-14T01:53:01Z RELEASE 0.1.10 make test GREEN (all modules, code = pin)
2026-11-14T02:07:40Z SMOKE-0110 MERGE 05cec67a plan/smoke/smoke.sh + README (not yet run live)
2026-11-14T02:12:19Z RELEASE 0.1.10 images PUSHED from a58b9699 (api/worker/web/backup); values tags bumped; APK 0.1.10/110 built. DEPLOY BLOCKED: kubectl OIDC login needs user browser
2026-11-14T02:12:19Z RUN23 END RESTART NEEDED (deploy + QA to successor after user login)
2026-11-14T02:30:00Z REL-0110 DEPLOY helm rev 16 from a58b9699; api/web/worker 0.1.10 Running, 0 restarts; dict hashes unchanged
2026-11-14T02:35:33Z REL-0110 VERIFY dict-refresh ok 252s, release 2026.11.14.3, vocab_list 5, list_entries 7748
2026-11-14T02:38:00Z AND-0110 DONE 0.1.10 APK on phone (user 0 + user 10); 0.1.9 crash was todayReviews NPE (AND-POSTSYNC)
2026-11-14T02:40:00Z AND-PERF DISPATCH writer-senior; phone: SQLite pool starved by todayReviews (30-116s on device)
2026-11-14T02:40:00Z AND-WIDGET DISPATCH writer; Glance Row>10 children crash
2026-11-14T02:41:55Z AND-WIDGET MERGE e328f8cc forecast row capped at 8 bars (Row>10 Glance crash)
2026-11-14T02:46:00Z REL-0110 VERIFY jpdb-deck-import visual-novel/3913 ok in 10m (scrape 2s/page, 0% shown whole time) -> WORKER-PROGRESS
2026-11-14T02:47:00Z SMOKE-0110 PASS 30 pass / 1 skip (jpdb-deck) / 0 fail on live 0.1.10; cleanup verified (note.deleted hides cards by design)
2026-11-14T02:51:18Z WORKER-PROGRESS MERGE d84a2cd9 per-page scrape progress, SIGTERM release, grace 45s; review APPROVE (minors -> WORKER-EMPTY)
2026-11-14T02:52:18Z AND-PERF REVIEW_CHANGES major: LoadGate retains LoadedQueue per key (~100MB leak); attempt 2 same writer
2026-11-14T02:56:45Z WORKER-EMPTY MERGE 0a4bca84 0-row import fails, per-stage progress, merged counter, Recreate, refund cap
2026-11-14T02:57:08Z SRV-404LOG MERGE df845c72 request logged once per call (unknown-route 404 double log)
2026-11-14T02:58:09Z AND-PERF MERGE c7d7d818 todayReviews index-driven (9.4s->2ms on real DB), LoadGate coalescing, no retention; review APPROVE r2
2026-11-14T03:02:04Z AND-STRICT MERGE c115825b main-thread IO off main, decks list one load, LoadGate tests de-flaked
2026-11-14T03:02:13Z RELEASE 0.1.11 PIN 265f76a8 (worktree scratchpad/rel0111): AND-PERF, AND-WIDGET, AND-STRICT, WORKER-PROGRESS, WORKER-EMPTY, SRV-404LOG
2026-11-14T03:20:00Z RELEASE 0.1.11 make test GREEN at 265f76a8; images api/worker/web/backup 0.1.11 PUSHED; values tags 0.1.11 + kanjidic sha256 3f87d938
2026-11-14T03:30:00Z RELEASE 0.1.11 DEPLOY BLOCKED: kubectl OIDC token expired (kubelogin needs browser); live stays 0.1.10
2026-11-14T03:35:00Z RELEASE 0.1.11 APK built ~/kotobaya-apk/kotobaya-0.1.11-debug.apk (code 111, debug cert ec6b7f45 ok); no adb device
2026-11-14T05:41:00Z REL-0111 helm rev 17 FAILED (worker strategy Recreate vs live rollingUpdate under SSA); kubectl patch removed rollingUpdate; rev 18 DEPLOYED from 265f76a8; api/web/worker 0.1.11 Running 0 restarts; JMdict sha re-pinned 1003f456
2026-11-14T05:43:30Z SMOKE-0111 PASS 30 pass / 1 skip / 0 fail; cleanup verified (2 user decks, no smoke cards)
2026-11-14T05:52:00Z QA-0111 logs api/worker/web clean (only smoke traffic; 413 expected); phone user 10: sync Completed, gate 126 ms, no crash/ANR/pool waits; app idle in window. Minors -> FOLLOWUPS (chart Recreate migration, startup StrictMode prefs, smoke ids file)
2026-11-14T05:53:00Z RUN25 END clean; no tickets dispatched
2026-11-14T06:11:59Z AND-QUEUE DISPATCH writer-senior; server queue OK (183 due), phone shows 0; Android-side
2026-11-14T06:21:19Z AND-QUEUE HANDOFF root cause: Home hero summed enabled-deck counts; due cards are deckless -> 0. Queue itself 183/10/1
2026-11-14T06:21:26Z AND-QUEUE CHECK_GREEN android
2026-11-14T06:23:20Z AND-QUEUE MERGE c2ef978d Home hero uses whole-queue counts; review APPROVE (minors -> FOLLOWUPS)
2026-11-14T06:23:20Z AND-REVIEW-STALE DISPATCH writer; Review tab Empty never refreshes on resume/sync (reviewer finding)
2026-11-14T06:27:35Z AND-REVIEW-STALE MERGE fb2a253d Review Empty refreshes on sync/resume/rollover; check green
2026-11-14T06:27:47Z RELEASE 0.1.12 PIN 85a260fb (APK only: AND-QUEUE, AND-REVIEW-STALE; server stays 0.1.11)
2026-11-14T06:28:31Z RELEASE 0.1.12 APK built ~/kotobaya-apk/kotobaya-0.1.12-debug.apk (code 112, cert ec6b7f45 ok); adb device dropped, waiting
2026-11-14T06:28:57Z AND-CARDHTML DISPATCH writer-senior; Review WebView loads empty data: URL; 0.1.12 install held for it
2026-11-14T06:40:59Z AND-CARDHTML HANDOFF: intercept 403'd own data: main frame; ReviewBackend had no dict/kanji lookup (blank fronts)
2026-11-14T06:41:07Z AND-CARDHTML CHECK_GREEN android (regression 4/4, payload 6/6)
2026-11-14T06:43:16Z AND-CARDHTML REVIEW_APPROVE + MERGE f639d113 (5 minors -> FOLLOWUPS)
2026-11-14T06:43:26Z RELEASE 0.1.12 RE-PIN b745fa53 (adds AND-CARDHTML; 85a260fb build never installed)
2026-11-14T06:43:50Z RELEASE 0.1.12 APK built ~/kotobaya-apk/kotobaya-0.1.12-debug.apk code 112 cert ec6b7f45 ok; waiting for adb device
2026-11-14T07:14:09Z RELEASE 0.1.12 INSTALL BLOCKED: no adb device for 40 min; HUMAN_CHECKS updated
2026-11-14T07:14:09Z RUN26 END 3 merged (AND-QUEUE, AND-REVIEW-STALE, AND-CARDHTML); 0.1.12 APK ready, device verify pending
2026-11-14T22:21:12Z RELEASE 0.1.12 INSTALLED on phone (adb install -r; cert ec6b7f45 match; 111->112, user 10 kept data)
2026-11-14T22:21:12Z QA-0112 logs: Takoboto notes_v2 csum query 37.7 s; Fatal Twelve installed_content_version 0<1 (no device content installer); deck rows show e:<seq>; search shows literal reading/meaning; GET /meta network x6 (settings)
2026-11-14T22:21:12Z ADR-026 DECIDE Android search merged into one list (PROVISIONAL)
2026-11-14T22:21:33Z AND-DECKCONTENT,AND-TAKO-PERF,AND-LABELS DISPATCH (writer-senior, writer-senior, writer)
2026-11-14T22:29:48Z AND-LABELS HANDOFF 710d5d5c merged search, deck labels, CardHit reading/meaning; /meta = ServerReachability probe reports (not settings)
2026-11-14T22:30:29Z AND-LABELS CHECK_GREEN + MERGE 710d5d5c
2026-11-14T22:34:01Z AND-DECKCONTENT HANDOFF a41edd82 ContentInstaller+WorkManager; rebase conflict DeckRepository -> attempt 2 (rebase + content-state UI); ADR-027 supersedes ADR-018
2026-11-14T22:41:57Z AND-TAKO-PERF HANDOFF findNotes per-note dict entry()+map lookups -> batched forms(); JVM miss 1379->91 ms; Takoboto deck = inbox id 1 by design
2026-11-14T22:43:47Z AND-DECKCONTENT HANDOFF attempt 2 (rebased 4f2da0ef, content-state UI + retry); reviewer dispatched
2026-11-14T22:46:06Z AND-TAKO-PERF CHECK_GREEN + MERGE abc4499a
2026-11-14T22:46:37Z AND-DECKCONTENT REVIEW_APPROVE (6 minors -> FOLLOWUPS)
2026-11-14T22:48:51Z AND-DECKCONTENT CHECK_GREEN + MERGE 09c74c17
2026-11-14T22:49:38Z RELEASE 0.1.13 PIN 09c74c17 APK built ~/kotobaya-apk/kotobaya-0.1.13-debug.apk code 113 cert ec6b7f45 ok (AND-LABELS, AND-TAKO-PERF, AND-DECKCONTENT); server stays 0.1.11
2026-11-14T23:44:49Z RELEASE 0.1.13 INSTALLED on phone; Fatal Twelve installed 10669 items; Takoboto send dup 4.0 s + insert 3.6 s (was 37.7 s) -> AND-TAKO-PERF2
2026-11-14T23:44:49Z AND-TAKO-PERF2 DISPATCH writer-senior
2026-11-14T23:45:32Z AND-REVIEW-SNAPPY DISPATCH writer-senior (user: reviews not instant)
2026-11-14T23:46:51Z RELEASE-BUILD test: assembleAnkidroidRelease with debug keystore via env OK (cert ec6b7f45, not debuggable). User: phone gets release builds from now on
2026-11-15T00:12:26Z AND-TAKO-PERF2 HANDOFF note change-log triggers + NoteWordIndex/ChecksumIndex; real-copy csum 0 ms, insert 13-15 ms; reviewer dispatched (triggers on note)
2026-11-15T00:12:36Z AND-TAKO-PERF2 CHECK_GREEN (awaiting review)
2026-11-15T00:16:01Z AND-TAKO-PERF2 REVIEW_CHANGES major: prune ignores cursorless consumer -> csum index misses pulled note during first warm; attempt 2 (+minors 3,5,6)
2026-11-15T00:19:34Z AND-REVIEW-SNAPPY HANDOFF async in-tap-order commit, in-memory queue, prerender; JVM answer 11 ms -> 0.3 ms; reviewer dispatched
2026-11-15T00:19:51Z AND-REVIEW-SNAPPY CHECK_GREEN (awaiting review)
2026-11-15T00:22:16Z AND-TAKO-PERF2 HANDOFF attempt 2 (major fixed per writer, lock scope, sfld index, rebuild test); re-review
2026-11-15T00:22:27Z AND-TAKO-PERF2 CHECK_GREEN attempt 2 (awaiting re-review)
2026-11-15T00:22:44Z AND-TAKO-PERF2 REVIEW_APPROVE r2 + MERGE a3f8d203
2026-11-15T00:22:53Z AND-REVIEW-SNAPPY REVIEW_APPROVE (6 minors -> FOLLOWUPS)
2026-11-15T00:26:05Z AND-REVIEW-SNAPPY CHECK_GREEN + MERGE 4899ca89
2026-11-15T00:27:10Z RELEASE 0.1.14 PIN cb54637e RELEASE build ~/kotobaya-apk/kotobaya-0.1.14-release.apk code 114 cert ec6b7f45 ok, not debuggable (AND-TAKO-PERF2, AND-REVIEW-SNAPPY)
2026-11-15T00:34:35Z WEB-TAKO-SEARCH DISPATCH writer; web Takoboto notes wrong text + preview fail; ADR-026 extended to web. kubectl OIDC expired (logs pending user login)
2026-11-15T00:40:45Z WEB-TAKO-SEARCH CHECK_GREEN + MERGE 3f2e0dbb (rowLabel jsonb key order bug; note types incl. deleted; /search merged). Needs web deploy (kubectl auth expired)
2026-11-15T00:41:30Z RELEASE web 0.1.12 PIN ef0e511f (web-only: WEB-TAKO-SEARCH; server/worker/core unchanged since 0.1.11) building/pushing; deploy needs kubectl OIDC login
2026-11-15T00:43:40Z RELEASE web 0.1.12 PUSHED; values web tag 0.1.12 (api/worker stay 0.1.11); helm upgrade pending kubectl login
2026-11-15T00:53:13Z RELEASE web 0.1.12 DEPLOYED helm rev 19 (pin ef0e511f; api/worker 0.1.11); phone 0.1.14 RELEASE build INSTALLED, startup OK (rebuild 43 ms, first paint 584 ms); API logs: only /meta probe reports
2026-11-15T00:55:12Z QA-0114 phone: Takoboto existing word dup hit 10 ms; new word csum 1 ms + insert 29 ms (was 37.7 s on 0.1.12, 7.6 s on 0.1.13)
2026-11-15T00:59:11Z QA-0114 phone reviews: tap->next card 0.1-1.6 ms, paint 36-71 ms, commit ~15 ms bg; ~20 answers, no crash. Web 0.1.12: 126 requests all 200 (cards/notes/dict/search/kanji). Only /meta probe reports
2026-11-15T01:08:08Z DICT freq_lists jpdb (Kuuuube v2.2 kana), bccwj (SUW+LUW), cejc (everyday conversation; Netflix had no pinnable source) pinned in values; helm rev 20; dict-refresh 01a1dcbf queued
2026-11-15T01:15:36Z WEB-PRIO DISPATCH writer-light (web decks list sorted desc + labels say higher-first; queue is lower-first)
2026-11-15T01:17:34Z WEB-REVIEW-FAST DISPATCH writer (web review reverts to Show answer on fast double click)
2026-11-15T01:18:02Z WEB-PRIO CHECK_GREEN + MERGE c1cc2264 (decks asc draw order, lower-first labels, mined hint)
2026-11-15T01:21:00Z WEB-ADD2DECK, AND-ADD2DECK DISPATCH writer x2 (user: no good way to add to deck; note view + dictionary buttons)
2026-11-15T01:21:00Z DICT objectstore OOMKilled (512Mi) during refresh -> values objectStore limit 2Gi (helm rev 21); refresh re-run
2026-11-15T01:21:24Z WEB-REVIEW-FAST HANDOFF optimistic grade, tap-order sends w/ answer id, epoch-guarded refetch, undo waits; reviewer + check running
2026-11-15T01:21:40Z WEB-REVIEW-FAST CHECK_GREEN (awaiting review)
2026-11-15T01:23:35Z WEB-REVIEW-FAST REVIEW_CHANGES 2 majors (pending lost on nav -> double review; refresh resets flipped card) -> attempt 2
2026-11-15T01:24:39Z DICT refresh OK release 2026.11.15.4 (219080 entries); freq decks offered jpdb/bccwj/cejc 1k-20k; JLPT deck-builds re-run succeeded after objectstore fix
2026-11-15T01:25:31Z WEB-ADD2DECK CHECK_GREEN + MERGE 6d178b19 (shared deck picker; note editor + dictionary add-to-deck/add-to-notes)
2026-11-15T01:25:49Z WEB-REVIEW-FAST HANDOFF attempt 2 (Outbox singleton, keep-current refresh, minors); re-review
2026-11-15T01:28:08Z WEB-REVIEW-FAST REVIEW_APPROVE r2 + CHECK_GREEN + MERGE 00c1d676
2026-11-15T01:28:39Z AND-DECKEND DISPATCH writer-senior (deck session loops on last card; possible repeat grading)
2026-11-15T01:32:41Z RELEASE web 0.1.13 DEPLOYED helm rev 23 (pin 4908783b: WEB-PRIO, WEB-ADD2DECK, WEB-REVIEW-FAST); rev 22 no-op (values sed missed web tag)
2026-11-15T01:33:26Z AND-DECKEND evidence: phone log 10:22:55-10:23:04 'rebuild ahead items=1' loop, 6 extra committed reviews of one learning card in a deck session; sent to writer
2026-11-15T01:33:34Z AND-ADD2DECK CHECK_GREEN + MERGE (see next line) (search detail: add to notes/deck, KbDeckPicker, offline ops)
2026-11-15T01:33:59Z AND-ADD2DECK MERGE ce4af9cf (first ff failed: main got c1e00bc8 docs/CLI-DAEMON.md from another session; branch restored from 6e2b5cf0 and rebased; docs-only, android check still valid)
2026-11-15T01:34:55Z PLAN CLI daemon tickets ARCH-CARD-TEXT, CLI-DAEMON, CLI-REVIEW, CLI-WATCH (docs/CLI-DAEMON.md) + API-GUIDE (user request) added
2026-11-15T01:35:21Z ARCH-CARD-TEXT DISPATCH architect(opus); CLI-DAEMON DISPATCH writer. Queued: API-GUIDE (next free slot), CLI-REVIEW + CLI-WATCH (after CLI-DAEMON)
2026-11-15T01:41:04Z CLI-DAEMON CHECK_GREEN + MERGE b03d46b6 (45 tests; jsonschema dev-only dep)
2026-11-15T01:41:09Z CLI-REVIEW DISPATCH writer-senior (on CLI-DAEMON b03d46b6)
2026-11-15T01:41:51Z AND-DECKEND HANDOFF (deck learn-ahead only if no deck due, never re-serve last answered; DeckDone + continueWithAll); reviewer dispatched
2026-11-15T01:44:05Z AND-DECKEND CHECK_GREEN (awaiting review)
2026-11-15T01:45:14Z AND-DECKEND REVIEW_APPROVE + MERGE f6bf579f (6 minors -> FOLLOWUPS)
2026-11-15T01:45:30Z API-GUIDE DISPATCH writer-light; RELEASE 0.1.15 PIN e76d2711 release build started (AND-ADD2DECK, AND-DECKEND)
2026-11-15T01:46:18Z RELEASE 0.1.15 APK built ~/kotobaya-apk/kotobaya-0.1.15-release.apk code 115 cert ec6b7f45 ok, not debuggable; waiting for adb
2026-11-15T01:47:25Z CLI-REVIEW HANDOFF (journal JSONL fsync, FIFO sender, replay; 65 tests); reviewer dispatched
2026-11-15T01:47:38Z CLI-REVIEW CHECK_GREEN (65 tests; awaiting review); CLI-WATCH DISPATCH writer-light
2026-11-15T01:47:51Z ADR-028 DECIDE (human): keep rule 5 learn-ahead re-serve in all-decks session
2026-11-15T01:49:16Z CLI-REVIEW REVIEW_CHANGES 2 majors (any 4xx terminal drops answers; journal append before durable write -> double review) -> attempt 2 (+minors 3-6)
2026-11-15T01:49:56Z API-GUIDE MERGE 7f92fcc7 (docs/API-GUIDE.md, 31 recipes checked vs openapi; cli/README link -> CLI-WATCH)
2026-11-15T01:51:12Z PLAN C-25, WEB-REVIEW-INFO, AND-REVIEW-INFO, AND-BACK (+ADR-029/030 provisional); AND-BACK next free slot, C-25 after ARCH-CARD-TEXT
2026-11-15T01:51:27Z CLI-WATCH CHECK_GREEN + MERGE afb9c55f (watch json/waybar/template, reconnect; cli/README.md with examples + API-GUIDE link)
2026-11-15T01:51:32Z AND-BACK DISPATCH writer
2026-11-15T01:52:47Z CLI-REVIEW HANDOFF attempt 2 (83 tests); re-review
2026-11-15T01:53:23Z CLI-REVIEW REVIEW_APPROVE r2 + CHECK_GREEN + MERGE 35d317fc (83 tests)
2026-11-15T02:07:07Z ARCH-CARD-TEXT HANDOFF (CardTextRenderer + QueueItem.text, server QueueText); reviewer + architect pass dispatched
2026-11-15T02:07:12Z AND-BACK HANDOFF (review back=undo once, search back stack, predictive back)
2026-11-15T02:08:13Z AND-BACK CHECK_GREEN + MERGE b621443a
2026-11-15T02:08:54Z ARCH-CARD-TEXT ARCHITECT_PASS CHANGES 1 major (pitch front omits reading vs HTML) + 5 minors; waiting for reviewer before attempt 2
2026-11-15T02:09:05Z ARCH-CARD-TEXT REVIEW_APPROVE (5 minors) + ARCHITECT CHANGES (pitch front) -> attempt 2
2026-11-15T02:33:06Z ARCH-CARD-TEXT HANDOFF attempt 2 (writer died uncommitted; orchestrator committed db36e0b5-rebased); checks + architect re-pass dispatched
2026-11-15T02:34:17Z ARCH-CARD-TEXT ARCHITECT_PASS CHANGES r2: pitch major fixed; clients not regenerated (contracts check red) -> attempt 3 (architect)
2026-11-15T02:43:58Z ARCH-CARD-TEXT CHECK_GREEN (contracts/core/server) + MERGE 40bd6b94 (3 attempts; clients regenerated)
2026-11-15T02:44:18Z C-25 DISPATCH architect(opus) (review extras: kanji example picker, QueueItem.decks/kanji_examples, deck_ids)
2026-11-15T03:11:23Z C-25 HANDOFF (KanjiExamplePicker, QueueItem.decks/deck_count/kanji_examples, deck_ids; 91 files incl clients); checks + reviewer + architect pass dispatched
2026-11-15T03:12:17Z C-25 ARCHITECT_PASS APPROVE (3 minors -> FOLLOWUPS); waiting on reviewer + checks
2026-11-15T03:12:29Z C-25 REVIEW_APPROVE + ARCHITECT APPROVE + CHECK_GREEN + MERGE ee1fecdd (4+3 minors -> FOLLOWUPS)
2026-11-15T03:12:46Z WEB-REVIEW-INFO DISPATCH writer; AND-REVIEW-INFO DISPATCH writer
2026-11-15T03:14:39Z WEB-REVIEW-INFO CHECK_GREEN + MERGE 48a817be (BackExtras: kanji examples + deck chips)
2026-11-15T03:20:33Z RELEASE 0.1.16 server+web PIN 99cf5a19 build started (ARCH-CARD-TEXT, C-25, WEB-REVIEW-INFO; fixes CLI daemon empty card text: deployed api 0.1.11 predates QueueItem.text). APK -> next
2026-11-15T03:29:41Z RELEASE 0.1.16 DEPLOYED helm rev 24 (pin 99cf5a19; api+web 0.1.16, worker stays 0.1.11). Live queue: 50/50 items carry text, kanji_examples + decks present; CLI daemon restarted, card text OK
2026-11-15T03:35:42Z AND-REVIEW-INFO HANDOFF (BackExtras offline picker + deck chips, Open in dictionary keepOnLeave); android check + perf-focused review dispatched
2026-11-15T03:37:42Z AND-REVIEW-INFO CHECK_GREEN + REVIEW_CHANGES 1 major (t.all() over 4 learning tables per kanji card on load/tap path) + deck lookup scans -> attempt 2 (same writer)
2026-11-15T03:38:42Z CLI-KBY DISPATCH writer (user spec; base = installed ~/.local/bin/kby; uncommitted repo draft scrapped per user)
2026-11-15T03:44:05Z CLI-KBY CHECK_GREEN (97 tests) + MERGE af5ad121 (daemon refuses hidden grades, hidden card publishes no answer; kby serve/do --rev)
2026-11-15T03:44:23Z AND-REVIEW-INFO REVIEW_CHANGES r2 (3 point queries per candidate word; 日 ~6-12k queries/card) -> ESCALATE writer-senior attempt 3 (per-build primitive sets)
2026-11-15T03:54:08Z AND-REVIEW-INFO HANDOFF attempt 3 (BackExtrasIndex per rebuild); check + review r3
2026-11-15T03:54:40Z AND-REVIEW-INFO REVIEW_APPROVE r3 + CHECK_GREEN + MERGE d04531d2 (3 attempts, 1 escalation; minors -> FOLLOWUPS)
2026-11-15T03:54:50Z RELEASE APK 0.1.17 PIN f058bbfc release build started (0.1.15 content + AND-BACK + AND-REVIEW-INFO)
2026-11-15T03:55:49Z RELEASE APK 0.1.17 built ~/kotobaya-apk/kotobaya-0.1.17-release.apk code 117 cert ec6b7f45 ok, not debuggable, pin f058bbfc; waiting for adb
2026-11-15T04:08:46Z RELEASE APK 0.1.17 INSTALLED (adb install -r; user 10 present). kby installed to ~/.local/bin (backup kby.bak-pre-serve), daemon restarted; serve + do refusal verified live
2026-11-15T04:23:13Z WEB-DECK-INSPECT DISPATCH writer (deck detail status filters + per-word/bulk actions; user request)
2026-11-15T04:23:13Z AND-DECK-INSPECT DISPATCH writer (offline deck detail filters + word.status_* actions)
2026-11-15T04:26:57Z WEB-DECK-INSPECT CHECK_GREEN + MERGE f30dfcdf (no chip counts: coverage is token-based; reader not refactored onto shared component)
2026-11-15T04:38:38Z AND-DECK-INSPECT CHECK_GREEN (flaky BackNavigationTest passed) + MERGE 0f380c62; raw e:<seq> -> placeholder; root cause = top60k device dict
2026-11-15T04:38:58Z AND-DECK-LABELS DISPATCH writer (fill missing deck labels via /dict/headwords + local cache)
2026-11-15T04:39:19Z RELEASE web 0.1.17 PIN c09a22ef build started (WEB-DECK-INSPECT)
2026-11-15T04:42:54Z RELEASE web 0.1.17 DEPLOYED helm rev 25 (api stays 0.1.16, worker 0.1.11)
2026-11-15T04:46:50Z AND-DECK-LABELS CHECK_GREEN + MERGE 6575708f
2026-11-15T04:46:50Z RELEASE APK 0.1.18 PIN 9a4fa136 release build started (AND-DECK-INSPECT + AND-DECK-LABELS)
2026-11-15T04:48:23Z RELEASE APK 0.1.18 INSTALLED (pin 9a4fa136, code 118, cert ec6b7f45 ok)
2026-11-15T04:57:35Z WRK-FREQ-MATCH DISPATCH writer-senior (freq-list decks fan kana term out to all homophones; jpdb/bccwj/cejc)
2026-11-15T05:11:36Z WRK-FREQ-MATCH HANDOFF + CHECK_GREEN (885 tests); reviewer dispatched
2026-11-15T05:13:14Z WRK-FREQ-MATCH REVIEW_CHANGES 2 majors (kana row falls back to next homophone when winner claimed: とき→鴇; katakana loanword beats word: もの→モノ) + tie minors -> attempt 2
2026-11-15T05:18:26Z AND-KANJI-DECKS DISPATCH writer (user request: kanji appears-in-decks above words-used-in)
2026-11-15T05:20:45Z WRK-FREQ-MATCH HANDOFF attempt 2 (890 tests green per writer); re-review dispatched
2026-11-15T05:24:27Z WRK-FREQ-MATCH REVIEW_CHANGES r2: prev 6 fixed; 2 new majors on real sources (own kanji-row rank tier too low: いくら→イクラ, つく→吐く; hiragana→katakana-only: ている→テール) -> attempt 3
2026-11-15T05:27:13Z AND-KANJI-DECKS CHECK_GREEN + MERGE 4b960c8f (chips display-only; no nav hook)
2026-11-15T05:42:18Z WRK-FREQ-MATCH HANDOFF attempt 3 (899 green; top-1k deferred+ambiguous jpdb 3, bccwj 5, cejc 9); re-review r3
2026-11-15T05:45:44Z WRK-FREQ-MATCH REVIEW_APPROVE r3 + CHECK_GREEN + MERGE 69ab28ff (3 attempts)
2026-11-15T05:45:57Z RELEASE worker 0.1.18 + APK 0.1.19 PIN 624d4305 build started (WRK-FREQ-MATCH; AND-KANJI-DECKS)
2026-11-15T05:48:35Z C-26 DISPATCH architect(opus) (keep going queue override + is_new; WEB/AND-KEEPGOING follow)
2026-11-15T06:02:25Z C-26 HANDOFF + CHECK_GREEN (contracts/core/server); reviewer + architect pass dispatched
2026-11-15T06:04:29Z C-26 REVIEW_APPROVE (5 minors) + ARCHITECT CHANGES 1 major (keep-going session end undefined) -> attempt 2 (incl. summary more_available)
2026-11-15T06:22:07Z C-26 ARCHITECT_PASS APPROVE r2 + CHECK_GREEN + MERGE ac6c1879 (2 attempts)
2026-11-15T06:22:07Z WEB-KEEPGOING DISPATCH writer; AND-KEEPGOING DISPATCH writer
2026-11-15T06:22:07Z RELEASE worker 0.1.18 pushed; helm upgrade BLOCKED: kube OIDC login expired (browser auth) — waiting on user
2026-11-15T06:23:38Z WEB-KEEPGOING CHECK_GREEN + MERGE aa8b54aa
2026-11-15T06:26:48Z RELEASE api+web 0.1.18 PIN c960233b build started (C-26 + WEB-KEEPGOING; worker 0.1.18 from 624d4305)
2026-11-15T06:32:14Z RELEASE api+web 0.1.18 pushed; values bumped (api/web/worker 0.1.18); deploy waits on kube login
2026-11-15T06:35:08Z AND-KEEPGOING CHECK_GREEN + MERGE c8e05a6c (AppNav touched for review/keepgoing route; deck sessions ignore keep going)
2026-11-15T06:35:09Z RELEASE APK 0.1.19 rebuild PIN cc8fe927 (adds AND-KEEPGOING; prior 0.1.19 build never installed)
2026-11-15T07:20:40Z RELEASE 0.1.18 DEPLOYED helm rev 26 (api/web c960233b, worker 624d4305); APK 0.1.19 INSTALLED (pin cc8fe927)
2026-11-15T07:22:06Z FREQ_REBUILD ran on live (freq_rank_changed 15879; 3 freq decks + 3 JLPT republished, content_version bump). Conversation deck そう: 16 -> 6 (kanji-row words 層/総/僧/草/沿う + 然う)
- run 30: user reported keep going stops + web unable to review. Root cause: deck entries never materialized (pendingEntries empty on server+device). C-27 (architect, 2 rounds, reviewer+architect approved) + AND-KEEPGOING-FIX (3 rounds, approved). Deployed api/web 0.1.19; APK 0.1.20 built.
2026-11-16T02:17:29Z RUN31 START user: web shows 3 cards, click -> stuck loading -> session over; web "something went wrong". Live: /review/queue ~6s every call; stats 14-76s, 504s; queue items=0 learning=3 next_due +2min
2026-11-16T02:17:29Z RC-SERVER DISPATCH architect(opus) (queue/stats latency root cause + server review-cycle audit)
2026-11-16T02:17:29Z RC-WEB DISPATCH writer-senior(opus) (waiting vs done, loading/error states, per-widget stats errors)
2026-11-16T02:17:29Z RC-AUDIT DISPATCH reviewer(opus) read-only audit of android + cli review cycle
2026-11-16T02:20:54Z RC-AUDIT DONE CHANGES: android 2 majors (dead card on rebuild throw; Summary never reloads), cli 2 majors (fetch fail=offline blocks answers; refresh blocks worker) -> RC-AND, RC-CLI
2026-11-16T02:20:54Z RC-CLI DISPATCH writer(sonnet); RC-AND queued (writer cap 3)
2026-11-16T02:22:03Z RC-WEB SCOPE_ADD web slowdown + flicker (sent to running writer)
2026-11-16T02:22:43Z RC-CLI CHECK_GREEN + MERGE (107 tests; degraded vs offline, backoff refetch, non-blocking refresh/keep_going)
2026-11-16T02:22:50Z RC-AND DISPATCH writer-senior(opus)
2026-11-16T02:27:29Z RC-WEB HANDOFF + CHECK_GREEN (122 tests); review dispatched (incl. flicker coverage)
2026-11-16T02:29:47Z RC-WEB REVIEW_CHANGES 2 majors (dispose leak via outbox afterSend; 409/410 answer pinned forever) + flicker causes (settling skeleton, wake error swap, home empty flash) -> attempt 2
2026-11-16T02:36:06Z REVIEW-UNLIMITED DECIDE ADR-034 opt-in toggle (user); ticket queued behind RC-SERVER/RC-AND/RC-WEB
2026-11-16T02:37:36Z RC-WEB REVIEW_CHANGES r2: prior findings verified fixed; 1 major (pending polls forever at per-session new cap) + 2 minors -> attempt 3
2026-11-16T02:40:50Z RC-WEB REVIEW_APPROVE r3 + CHECK_GREEN (134) + MERGE (3 attempts); 1 minor -> FOLLOWUPS
2026-11-16T02:43:55Z RELEASE web 0.1.23 DEPLOYED helm rev 32 (pin dd2f3ade, RC-WEB); api 0.1.20/worker 0.1.18 unchanged
2026-11-16T02:46:44Z RC-AND REVIEW_APPROVE + CHECK_GREEN + MERGE (1 attempt); 3 minors + flaky BackNavigationTest -> FOLLOWUPS
2026-11-16T02:54:27Z RC-SERVER REVIEW_APPROVE + CHECK_GREEN + MERGE (1 attempt). Root cause: jOOQ array bind any(?::uuid[]) re-parsed per row; queue 6s->~0.1-0.2s, known-words 44s->2s; stats capped 3 conns
2026-11-16T02:59:46Z RELEASE api 0.1.21 DEPLOYED helm rev 33 (pin 296e5a16, RC-SERVER+RC-CLI+RC-AND on main); live queue 6s -> ~225 ms server-side, stats 14-76s -> 0.6-1.3s; web 0.1.23, worker 0.1.18
2026-11-16T02:59:46Z RELEASE CLI daemon reinstalled (uv tool, pin 296e5a16), kotobaya.service restarted active
2026-11-16T02:59:46Z RELEASE APK 0.1.25 built (pin 4ed6beaa, code 125, cert ec6b7f45 ok) ~/kotobaya-apk/kotobaya-0.1.25-release.apk; no adb device -> install pending
2026-11-16T02:59:46Z REVIEW-UNLIMITED DISPATCH architect(opus)
2026-11-16T03:17:46Z REVIEW-UNLIMITED HANDOFF (android red only on known-flaky BackNavigationTest); review dispatched. AND-FLAKY-BACKNAV DISPATCH writer(sonnet) — flaky now fails every full run, blocks green
2026-11-16T03:18:28Z REVIEW-UNLIMITED REVIEW_APPROVE (3 minors -> FOLLOWUPS); merge waits on AND-FLAKY-BACKNAV for green android
2026-11-16T03:50:24Z APK 0.1.25 sent to user (SendUserFile) for self-install. Backlog queued: RC-FOLLOW-SERVER, RC-FOLLOW-WEB DISPATCH; RC-FOLLOW-AND queued (deps AND-FLAKY-BACKNAV, REVIEW-UNLIMITED)
2026-11-16T03:51:45Z RC-FOLLOW-WEB CHECK_GREEN + MERGE
2026-11-16T03:51:59Z CLI-KEEPGOING DISPATCH writer(sonnet) (user: keep going in CLI + simple kby)
2026-11-16T03:53:55Z CLI-KEEPGOING CHECK_GREEN + MERGE (110 tests)
2026-11-16T03:59:38Z RC-FOLLOW-SERVER CHECK_GREEN + MERGE (summary exempt from stats gate; guard regex widened; 4x stats burst not reproduced)
2026-11-16T04:00:30Z CARD-KIND-BADGE ticket (user); WEB + CLI DISPATCH writer(sonnet); AND queued behind AND-FLAKY/REVIEW-UNLIMITED
2026-11-16T04:01:41Z CARD-KIND-BADGE-WEB CHECK_GREEN + MERGE (field card.note.type; labels Vocab/Kanji/Phrase/Grammar/Sentence/Custom)
2026-11-16T04:02:37Z CARD-KIND-BADGE-CLI CHECK_GREEN + MERGE
2026-11-16T04:05:04Z AND-DICT-FALLBACK SHELVED (user)
2026-11-16T04:07:58Z AND-FLAKY-BACKNAV CHECK_GREEN(writer, 3 full runs) + MERGE (test-only: lost global snapshot apply across Robolectric tests)
2026-11-16T04:14:56Z AND-BATCH (CARD-KIND-BADGE-AND + RC-FOLLOW-AND) DISPATCH writer(sonnet)
2026-11-16T04:15:05Z REVIEW-UNLIMITED CHECK_GREEN (contracts/core/server/web/android) + MERGE (ADR-034)
2026-11-16T04:21:42Z RELEASE api 0.1.22 + web 0.1.24 DEPLOYED helm rev 34 (pin c8fc3151: REVIEW-UNLIMITED, CARD-KIND-BADGE-WEB, RC-FOLLOW-SERVER/WEB); queue ~240 ms server-side
2026-11-16T04:27:04Z AND-BATCH REVIEW_CHANGES 2 majors (stale shortcut ignores suspended/buried/override; check not applied to the card actually shown) -> attempt 2 (revert to always-rebuild if not cleanly safe)
2026-11-16T04:31:16Z AND-BATCH attempt 2 (item 3 reverted to always-rebuild = RC-AND reviewed behaviour) CHECK_GREEN + MERGE
2026-11-16T04:32:22Z RELEASE APK 0.1.26 built (pin ccb2bd78, code 126, cert ok) + sent to user
2026-11-16T05:23:41Z SYNC-NEWSTATE DISPATCH architect(opus) — phone shows New on cards reviewed via web/CLI yesterday; server says review/is_new=false
2026-11-16T05:31:23Z SYNC-NEWSTATE DONE no bug: 'New' cards are never-reviewed reading siblings of yesterday's meaning cards; phone sync verified via op log (seq 1-115668). Follow-up: show Meaning/Reading in badge
2026-11-16T05:42:42Z ONE-CARD DECIDE (user): reading+meaning = one card, reviewed once. Design dispatch architect(opus)
2026-11-16T05:48:23Z ONE-CARD design done -> ADR-035 recorded; C-28+ONE-CARD-CORE DISPATCH architect(opus)
2026-11-16T06:11:11Z C-28+ONE-CARD-CORE HANDOFF (contracts+core green; server/android tests need updates); review dispatched; ONE-CARD-SRV + ONE-CARD-AND DISPATCH writer-senior from t/C-28-ONE-CARD-CORE (merge all together when green)
2026-11-16T06:13:10Z C-28+ONE-CARD-CORE REVIEW_APPROVE (5 minors); merge together with SRV+AND
2026-11-16T06:29:36Z ONE-CARD-SRV HANDOFF (server 490 + acceptance green); review dispatched
2026-11-16T06:31:14Z ONE-CARD-SRV REVIEW_CHANGES 1 major (retired learning cards counted via QueueLoader candidates -> phantom learning/waiting) -> attempt 2
2026-11-16T06:32:53Z ONE-CARD-AND REVIEW_APPROVE (3 minors -> FOLLOWUPS); waits for ONE-CARD-SRV attempt 2 to merge together
2026-11-16T06:40:35Z ONE-CARD-SRV REVIEW_CHANGES r2: loader dropping retired keeps their today-reviews (budget/bury divergence vs Android) -> attempt 3: keep in QueueInput, exclude only in laterLearning/next-due/forecast
2026-11-16T06:48:26Z ONE-CARD-SRV REVIEW_APPROVE r3. Chain rebased C-28 -> SRV -> AND onto main; full module checks running on combined tip
2026-11-16T06:53:18Z C-28+ONE-CARD-CORE, ONE-CARD-SRV, ONE-CARD-AND MERGE (all modules green; cli 1 flaky timing test, 3 reruns green -> FOLLOWUPS). ONE-CARD-WEB next
2026-11-16T06:54:31Z ONE-CARD-WEB CHECK_GREEN + MERGE
2026-11-16T06:57:57Z RELEASE built api 0.1.23 (pin b7a3a0cc), web 0.1.25 (pin a450bdf8), APK 0.1.27 (pin b7a3a0cc, sent). helm deploy BLOCKED: kube OIDC login expired (needs user browser login)
2026-11-16T10:11:04Z KIND-CHIP-OVERLAP (user, bug in 0.1.27: card text under type chip on Android) DISPATCH AND + WEB writers
2026-11-16T10:19:28Z KIND-CHIP-OVERLAP-WEB CHECK_GREEN + playwright chip-overlap 6 passed + orchestrator viewed PNGs + MERGE
2026-11-16T10:22:51Z RELEASE web 0.1.26 DEPLOYED helm rev 36 (pin dc632544, chip row fix); api 0.1.23
2026-11-16T10:37:28Z KIND-CHIP-OVERLAP-AND CHECK_GREEN + real-layout bounds test + orchestrator viewed PNGs + MERGE
2026-11-16T10:38:24Z RELEASE APK 0.1.28 built (pin 6607e50d, code 128, cert ok) — waiting for user to plug in phone
2026-11-16T11:36:35Z SHELL DECIDE (human): Android moves entirely to web UI in WebView; Compose deleted; design dispatch architect
2026-11-16T11:46:22Z SHELL design DONE: 24 tickets (docs/ANDROID-SHELL.md, ADR-036) committed fcabf88a
2026-11-16T11:46:22Z SHELL-C1 DISPATCH architect
2026-11-16T11:46:22Z SHELL-A1 DISPATCH writer(sonnet) [gradle lane]
2026-11-16T11:56:19Z SHELL-A1 CHECK_GREEN (web+android) + MERGE
2026-11-16T12:03:32Z SHELL-L9 NEW ticket (user: cards from conversation list lack dict info even when dict up to date; likely top60k scope gap) -> L2/L5 depend on it; supersedes AND-DICT-FALLBACK
2026-11-16T12:17:32Z SHELL-C1 HANDOFF + CHECK_GREEN (contracts; additive, contracts/api/shell only); reviewer dispatched (architect authored, so reviewer is the 2nd pass)
2026-11-16T12:19:57Z SHELL-C1 REVIEW_CHANGES 2 majors (dict lookup vs L9 gap-fill; createDeck list is server_only) -> attempt 2
2026-11-16T12:26:55Z SHELL-C1 REVIEW_CHANGES r2: 'scope all authoritative' recreates L9 symptom when phone release < server -> attempt 3 (+search forward when online)
2026-11-16T12:30:48Z SHELL-C1 attempt 3 CHECK_GREEN + MERGE (fix targeted the r2 major exactly; no 3rd review round)
2026-11-16T12:31:05Z SHELL-A2 DISPATCH writer-senior [gradle lane]
2026-11-16T12:31:05Z SHELL-W1 DISPATCH writer-senior [web, no gradle]
2026-11-16T12:47:01Z SHELL-W1 CHECK_GREEN (web) + MERGE; note: web/Dockerfile must COPY clients/typescript-shell + contracts/api/shell/ops.json -> SHELL-W1b
2026-11-16T12:47:27Z SHELL-W2 DISPATCH writer(sonnet) [web lane; +Dockerfile, canLogout]
2026-11-16T12:56:22Z SHELL-A2 CHECK_GREEN (run 2; run 1 hit known flaky SyncCoordinatorTest.concurrentTriggersRunOneSync -> FOLLOWUPS) + MERGE
2026-11-16T12:56:22Z SHELL-W2 CHECK_GREEN (web) + MERGE (playwright 360/412 + screenshots viewed by writer)
2026-11-16T12:56:40Z SHELL-P1 DISPATCH writer-senior [gradle lane, server]
2026-11-16T12:56:40Z SHELL-W3 DISPATCH writer [web lane]
2026-11-16T13:01:09Z SHELL-W3 CHECK_GREEN (web 270) + MERGE (small unowned edit lib/state/dictionary.svelte.ts accepted: same module, web lane serial); SHELL-W4 DISPATCH writer
2026-11-16T13:06:11Z SHELL-P1 CHECK_GREEN (server) + MERGE; all 17 goldens matched contract expect
2026-11-16T13:06:11Z SHELL-P2 DISPATCH writer-senior [gradle lane]
2026-11-16T13:23:43Z SHELL-P2 CHECK_GREEN + MERGE (flaky SyncCoordinatorTest fixed test-side)
2026-11-16T13:23:43Z SHELL-W4 CHECK_GREEN + orchestrator viewed review/settings PNGs + MERGE; fixes live web bug: Keep going reset itself ($effect re-ran start) -> ship in next web release
2026-11-16T13:23:43Z SHELL-L9 DISPATCH writer-senior [gradle lane]
2026-11-16T13:41:31Z SHELL-L9 CHECK_GREEN + MERGE. Cause confirmed: top60k package built before list-deck rebuilds -> list words outside top 60k absent. New WRK-DICT-LISTSEQ (worker, non-gradle) for the root fix
2026-11-16T13:41:31Z SHELL-L1 DISPATCH writer [gradle lane]; WRK-DICT-LISTSEQ DISPATCH writer [worker lane]
2026-11-16T13:47:39Z WRK-DICT-LISTSEQ CHECK_GREEN (worker 900) + MERGE; needs worker release + dict-refresh run
2026-11-16T13:58:24Z SHELL-L1 CHECK_GREEN + MERGE (identity/settings/presets/note types/client errors local)
2026-11-16T13:58:24Z SHELL-L2 DISPATCH writer-senior [gradle lane]
2026-11-16T14:08:30Z RELEASE worker 0.1.19 DEPLOYED helm rev 37 (pin f4492cf4, WRK-DICT-LISTSEQ). dict-refresh NOT queued: CLI token lacks import scope -> asked user to trigger
2026-11-16T14:18:59Z SHELL-L2 HANDOFF + CHECK_GREEN (517); reviewer dispatched
2026-11-16T14:18:59Z SHELL-L3 DISPATCH architect from t/SHELL-L2 (chain) [gradle lane]
2026-11-16T14:22:48Z SHELL-L2 REVIEW_APPROVE (5 minors: #1,#3,#4 -> SHELL-L2b; #2 -> FOLLOWUPS) + MERGE
2026-11-16T14:49:49Z SHELL-L3 HANDOFF; check RED x2 after rebase = infra (/tmp usrquota tmpfs full: old rel worktrees + leaked /tmp/parity* dirs, ~500/run) -> cleaned, re-run GREEN; reviewer dispatched
2026-11-16T14:49:49Z SHELL-A3 DISPATCH writer-senior from t/SHELL-L3 (chain) [gradle lane]
2026-11-16T14:51:01Z SHELL-L3 REVIEW_APPROVE (6 minors -> L2b/FOLLOWUPS) + MERGE (re-rebased over plan-only commit, no code change)
2026-11-16T15:08:27Z SHELL-A3 HANDOFF + CHECK_GREEN; reviewer dispatched. New dep androidx.webkit 1.12.1
2026-11-16T15:08:27Z SHELL-A5 DISPATCH writer-senior from t/SHELL-A3 (chain) [gradle lane]; plan: first-test APK after A3 approve (A5 if close)
2026-11-16T15:11:02Z SHELL-A3 REVIEW_APPROVE (6 minors -> SHELL-A4 notes) + MERGE
2026-11-16T15:21:42Z SHELL-A5 CHECK_GREEN + MERGE (local ops w/o handler forwarded online until L4-L8; L-1 temporarily relaxed)
2026-11-16T15:22:48Z RELEASE APK 0.1.29 built (pin 6c79233c, code 129, cert ok, 441 web assets) — SHELL first phone test; waiting for user to plug in
2026-11-16T15:23:03Z SHELL-A4 DISPATCH writer [gradle lane] (+A3 review minors)
2026-11-16T15:35:28Z SHELL-A4 CHECK_GREEN + MERGE (back protocol, deep links, intake trampoline, A3 minors)
2026-11-16T15:36:34Z RELEASE APK 0.1.30 built (pin e11d613e, code 130, cert ok; +A4) supersedes 0.1.29 (never installed)
2026-11-16T15:36:34Z SHELL-L2b DISPATCH writer [gradle lane] (tmp leak fix first)
2026-11-16T15:45:19Z RELEASE APK 0.1.30 INSTALLED (users 0+10); smoke: launches into SPA signed in, Today counts 0/7/0 == server; no crash in logcat
2026-11-16T15:48:44Z SHELL-L2b CHECK_GREEN + MERGE (tmp leak fixed at source: 0 parity dirs after run; keepGoingNewBatch=limit)
2026-11-16T15:49:20Z USER 0.1.30 feedback: 'looks pretty good', UI works; no light/dark. Logs clean (sync pushing reviews). SHELL-DARK DISPATCH writer
2026-11-16T15:57:54Z SHELL-DARK CHECK_GREEN + MERGE
2026-11-16T15:59:56Z SHELL-DARK verified on device (live toggle: brightness 38 dark / 238 light); APK 0.1.31 INSTALLED (pin e065f743)
2026-11-16T15:59:56Z USER: toast covers Show answer -> WEB-TOAST-REVIEW DISPATCH writer [web]. USER: unlimited review loop rework (28 shown, waiting screen) -> confirming design with user
2026-11-16T16:04:24Z WEB-TOAST-REVIEW CHECK_GREEN + MERGE (toast lifted above review action bar; playwright hit-test 4/4)
2026-11-16T16:05:22Z SHELL-A6 DISPATCH writer [gradle lane]
2026-11-16T16:05:22Z HOLD: review-loop rework (unlimited on/off) + lock-portrait setting await user confirmation of outlined loop A/B
2026-11-16T16:11:15Z USER CONFIRMED review loop A/B (+tweak: unlimited still has new-goal 40/40 -> goal_reached + Keep going) and Lock-to-portrait. Design dispatch architect (ADR-037)
2026-11-16T16:13:39Z USER: FSRS algorithm stays unchanged (no tweaks); everything else approved
2026-11-16T16:16:38Z SHELL-A6 CHECK_GREEN + MERGE (ShellEvents unwired -> folded into SHELL-A7); SHELL-A7 DISPATCH writer [gradle lane]
2026-11-16T16:18:01Z LOOP design done (ADR-037, docs/REVIEW-LOOP.md, 8 tickets); LOOP-C DISPATCH architect
2026-11-16T16:19:16Z USER: order unchanged; SHELL-Z2 (web bundle OTA) now in scope, scheduled last
2026-11-16T16:21:26Z USER: keep new/review interleave as a smart proportional mix in BOTH modes (both pools hit zero together); new_per_session dropped OK; keep-going per device confirmed. Sent to LOOP-C (post-cap backlog-first rule kept)
2026-11-16T16:24:24Z USER: confirmed Anki-style cap (learning/relearning steps don't consume reviews_per_day); all loop side effects accepted
2026-11-16T16:29:31Z USER: approved SHELL-Z1 (delete Compose UI) without a further phone-check gate
2026-11-16T16:33:21Z SHELL-A7 CHECK_GREEN + MERGE (device settings ops + ShellEvents wired)
2026-11-16T16:36:27Z SHELL-A7 NOTE: orchestrator merged after a RED re-check (1/647 failed; merge not gated on exit code). Re-ran make check on main post-merge: GREEN -> unidentified flake. Merge commands now gated on check exit code.
2026-11-16T16:41:59Z LOOP-C REVIEW_CHANGES 1 major (headline ready depends on keep_going) + stride mix on remaining, legacy new frozen, display prefs unpaired -> attempt 2
2026-11-16T16:48:34Z LOOP-C attempt 2 CHECK_GREEN + MERGE (fix exactly the r1 major + agreed minors)
2026-11-16T16:49:05Z LOOP-CORE DISPATCH writer-senior [gradle lane]
2026-11-16T16:49:05Z LOOP-WEB+PORTRAIT-WEB DISPATCH writer-senior [web]
2026-11-16T16:49:05Z LOOP-CLI DISPATCH writer [cli]
2026-11-16T16:55:53Z LOOP-CLI CHECK_GREEN (120) + MERGE; no legacy fallback (server_too_old) -> release api before daemon reinstall
2026-11-16T17:00:10Z LOOP-CORE HANDOFF + CHECK_GREEN (core jvm+js); reviewer dispatched; chain CORE->SRV->AND merges together (core change breaks server/android tests alone)
2026-11-16T17:00:10Z LOOP-SRV DISPATCH writer from t/LOOP-CORE [gradle lane]
2026-11-16T17:01:55Z LOOP-CORE REVIEW_APPROVE (3 minors -> FOLLOWUPS); waits to merge with SRV+AND
2026-11-16T17:07:41Z LOOP-WEB+PORTRAIT-WEB CHECK_GREEN + orchestrator viewed Home PNGs + MERGE; LOOP-WEB2 follow-up (explicit quotas on done screen, drop per-session row, fake-shell v2). NOTE: web now needs local_api 2 -> don't build an APK before LOOP-AND
2026-11-16T17:09:53Z LOOP-SRV HANDOFF green (11 goldens re-recorded); 1 scenario expect wrong (fuzzed due order; server correct) -> allowlisted, LOOP-C2 later. LOOP-AND DISPATCH writer from t/LOOP-SRV [gradle lane]
2026-11-16T17:11:50Z LOOP-WEB2 CHECK_GREEN + MERGE (explicit quota line, per-session row removed, fake-shell v2)
2026-11-16T17:12:12Z LOOP-AND(+PORTRAIT-AND) DISPATCH writer from t/LOOP-SRV [gradle lane]
2026-11-16T17:33:23Z LOOP-CORE+SRV+AND(+PORTRAIT-AND) chain: contracts/core/server/android/web GREEN on rebased tip -> MERGE
2026-11-16T17:33:43Z RELEASE building api 0.1.24 + web 0.1.27 (pin 91140ed8: LOOP chain + LOOP-WEB/WEB2 + toast fix); then helm, CLI reinstall, APK 0.1.32
2026-11-16T17:42:19Z RELEASE api 0.1.24 + web 0.1.27 PUSHED (pin 91140ed8); values bumped; helm BLOCKED (kube OIDC expired) -> user login. APK 0.1.32 BUILT (pin 91140ed8, cert ok, min_local_api 2) waiting for phone
2026-11-16T17:42:19Z SHELL-L4 DISPATCH writer-senior [gradle lane]
2026-11-16T18:00:02Z SHELL-L4 HANDOFF; sent back 1b for ShellModule CardQuery wiring. New CORE-WS-UPDATED; parity scenarios folded into LOOP-C2
2026-11-16T18:07:10Z SHELL-L4 attempt 1b CHECK_GREEN; reviewer dispatched
2026-11-16T18:07:10Z SHELL-L5 DISPATCH writer-senior from t/SHELL-L4 (chain) [gradle lane]
2026-11-16T18:08:49Z SHELL-L4 REVIEW_CHANGES 1 major (bulk query ignores live smart decks -> negated smart-deck query would suspend everything) + minors -> attempt 2. NOTE L5 branched from L4: rebase L5 after
2026-11-16T18:16:28Z SHELL-L4 attempt 2 CHECK_GREEN + MERGE (smart decks compiled like server; negated smart-deck test)
2026-11-16T18:35:41Z SHELL-L5 HANDOFF + CHECK_GREEN (rebased over L4 attempt 2); reviewer dispatched (focus: add_kanji parity with server createNote)
2026-11-16T18:35:41Z SHELL-L6 DISPATCH writer from t/SHELL-L5 (chain) [gradle lane]
2026-11-16T18:37:27Z SHELL-L5 REVIEW_APPROVE (7 minors -> SHELL-L5b + LOOP-C2 doc clarification) + MERGE; server createNote makes no kanji notes, so phone parity correct
2026-11-16T18:53:39Z SHELL-L6 CHECK_GREEN + MERGE (listCards via LocalCardQuery, listNotes); AND-FLAKY-DUPCHECK filed; SHELL-L7 DISPATCH writer-senior [gradle lane]
2026-11-16T19:12:23Z SHELL-L7 CHECK_GREEN + MERGE (decks local; list->forward); deck parity scenarios -> LOOP-C2. SHELL-L8 DISPATCH writer [gradle lane]
2026-11-16T19:26:22Z SHELL-L8 CHECK_GREEN + MERGE: RouteTableTest PENDING empty -> every local op answered on the phone
2026-11-16T19:27:45Z RELEASE APK 0.1.33 BUILT (pin 41433ef4: loop + portrait + all offline ops L4-L8; cert ok) supersedes 0.1.32 (not installed)
2026-11-16T19:27:45Z SHELL-L5b DISPATCH writer [gradle lane]
2026-11-16T19:36:26Z SHELL-L5b CHECK_GREEN + MERGE (search index over gap cache; forwarded header). SHELL-Z1 DISPATCH writer [gradle lane]
2026-11-16T19:57:06Z SHELL-Z1 CHECK_GREEN (android re-run + web + tokens) + MERGE: Compose UI deleted (-23k lines, APK 16.6->11.3 MB); SPEC.md Android paragraph updated by writer (reflects human ADR-036); 1 race flake -> AND-MATERIALIZE-RACE. DISPATCH AND-MATERIALIZE-RACE [gradle lane]
2026-11-16T20:17:29Z AND-MATERIALIZE-RACE CHECK_GREEN + MERGE: TEST ARTIFACT (JDBC IN_MEMORY shared connection); prod AndroidSqliteDriver BEGIN IMMEDIATE serializes writers; new production-driver race tests 50x
2026-11-16T20:18:16Z RELEASE APK 0.1.34 BUILT (pin 6d357310: +Z1 Compose deleted, single launcher, 25.0 MB incl web; cert ok) supersedes 0.1.33 (not installed)
2026-11-16T20:18:16Z CORE-WS-UPDATED DISPATCH writer-senior [gradle lane]
2026-11-16T20:19:17Z CORE-WS-UPDATED DECIDE close: contract (SYNC.md 7.5, 11.4) defines updated_at as local/non-comparable; CCR would be breaking with no user-visible gain. ActionOpsTest exclusion is correct. AND-FLAKY-DUPCHECK DISPATCH writer [gradle lane]
2026-11-16T20:26:18Z AND-FLAKY-DUPCHECK CHECK_GREEN + MERGE (JDBC driver classloader registration); LOOP-C2 DISPATCH architect
2026-11-16T20:26:40Z LOOP-C2 SPLIT -> C2 (fix expect + createNote doc + L4 scenarios) and C2b (decks/listCards/struggle)
2026-11-17T00:30:00Z RELEASE api 0.1.24 + web 0.1.27 DEPLOYED helm rev 38 (pin 91140ed8); CLI daemon + kby reinstalled from pin, daemon active
2026-11-17T00:31:00Z RELEASE APK 0.1.34 INSTALLED (code 134, user 10 launch clean, no crash buffer entries)
2026-11-17T00:33:00Z LOOP-C2 DISPATCH architect (re-dispatch, run 33; previous session ended before start). Stale worktrees for merged branches removed
2026-11-17T00:40:00Z RUN DECIDE user: pause after LOOP-C2 merges; C2b and Z2 held
2026-11-17T01:20:00Z LOOP-C2 CHECK_GREEN (contracts/server/android after rebase) + MERGE; 24 parity match, no allowlist; 2 questions -> FOLLOWUPS. RUN PAUSED per user
2026-11-19T03:12:22Z CLI FIX daemon crash-loop (editable kotobaya_client in deleted scratchpad); reinstalled non-editable from main (cli unchanged since pin), daemon active
2026-11-19T03:13:55Z WEB-REVIEW-FLOW CHECK_GREEN + MERGE (seamless splice fetch, keep going per visit in-memory, example rows one line)
2026-11-19T03:14:07Z CLI-KG-SCOPE DISPATCH writer-light
2026-11-19T03:16:00Z CLI-KG-SCOPE REVIEW_CHANGES attempt 2: daemon-run scope still leaks over quota; add 10-min idle reset. DECIDE ADR-039 amended (idle 10 min); WEB-KG-IDLE filed
2026-11-19T03:17:34Z CLI-KG-SCOPE attempt 2 CHECK_GREEN + MERGE (in-memory keep going, 10-min idle reset)
2026-11-19T03:17:54Z CLI daemon reinstalled from main 0ccb8f01 (CLI-KG-SCOPE), active
2026-11-19T03:18:29Z WEB-KG-IDLE CHECK_GREEN + MERGE (lazy 10-min idle expiry, resume check)
2026-11-19T03:21:38Z C-28 DECIDE ADR-042 (kanji exemptions + furigana fronts, user); C-28/CORE-28 tickets amended, architect messaged
2026-11-19T03:23:16Z BUG-ADD2DECK-500 BLOCK not reproducible (10 scenarios); needs phone logcat; no adb device
2026-11-19T03:29:40Z BUG-ADD2DECK-500 UNBLOCK: logcat+client_error reports = svelte effect_update_depth_exceeded on /dictionary (web+android), not HTTP; rescoped to web, DISPATCH writer-senior attempt 2
2026-11-19T03:38:25Z WEB-MORE-CLOSE DISPATCH writer-light (user request)
2026-11-19T03:40:07Z WEB-MORE-CLOSE CHECK_GREEN + MERGE
2026-11-19T03:40:38Z BUG-ADD2DECK-500 attempt 2 CHECK_GREEN + MERGE (backStack registerOverlay push in untrack: every Dialog looped; +error page)
2026-11-19T03:41:28Z C-28 HANDOFF + reviewer dispatched. RUN DECIDE user: single release after all round tickets
2026-11-19T03:47:01Z C-28 REVIEW_CHANGES 2 major (backfill never mints; unit>budget starvation) + minors; DECIDE ADR-043 provisional; attempt 2 to architect
2026-11-19T03:55:14Z C-28 REVIEW_APPROVE r2 + CHECK_GREEN + MERGE; 2 minors -> FOLLOWUPS (backfill share asked user)
2026-11-19T03:56:41Z DECIDE ADR-044 (user: backfill half the goal); C-28b DISPATCH architect
2026-11-19T03:56:57Z WEB-28 DISPATCH writer (+front_furigana)
2026-11-19T04:01:50Z WEB-28 HANDOFF; sent back for screenshots (deleted) + loop mock; CORE-28 note: ruby furigana
2026-11-19T04:05:23Z WEB-28 attempt 3 CHECK_GREEN + MERGE (until_stop bar, DeckProgress + legend, KanjiParts; screenshots viewed)
2026-11-19T04:07:29Z C-28b REVIEW_CHANGES 1 major (deckless own turn bypasses backfill share); attempt 2; 2 minors -> FOLLOWUPS
2026-11-19T04:18:35Z C-28b REVIEW_APPROVE r3 + CHECK_GREEN + MERGE; CORE-28 DISPATCH writer-senior
2026-11-19T04:30:28Z STATE resume section written (context limit approaching)
2026-11-19T04:53:32Z CORE-28 CONTRACT_CHANGE_REQUEST (4 rule-4/5 cases; rebuild!=tail in 2); code green; C-28c DISPATCH architect
2026-11-19T05:10:14Z C-28c resumed after usage-limit reset (same architect agent)
2026-11-19T05:17:17Z C-28c HANDOFF (4 cases resolved, rebuild=tail normative) + CHECK_GREEN; reviewer dispatched
2026-11-19T05:18:45Z C-28c REVIEW_CHANGES 1 major (round-robin restart breaks rebuild=tail in default deck+deckless tier); attempt 2
2026-11-19T05:23:49Z C-28c attempt 2 HANDOFF (turn order by t(S) replaces round-robin) + green; reviewer r2
2026-11-19T05:24:58Z C-28c REVIEW_CHANGES r2: 1 major (unrequired deckless kanji keep t=0 -> deck starves) + tie-break minor folded in; attempt 3
2026-11-19T05:26:42Z C-28c attempt 3 HANDOFF + green; reviewer r3
2026-11-19T05:28:10Z C-28c REVIEW_APPROVE r3 + CHECK_GREEN + MERGE; CORE-28 attempt 2 (apply 5 resolutions)
2026-11-19T05:40:18Z CORE-28 attempt 2 HANDOFF + CHECK_GREEN (core); reviewer dispatched
2026-11-19T05:47:24Z CORE-28 REVIEW_APPROVE + MERGE; minors -> C-28d + CORE-28b; DISPATCH C-28d architect, SRV-28 writer, AND-28 writer
2026-11-19T06:05:57Z SRV-28 HANDOFF + CHECK_GREEN; reviewer dispatched (minting via op path, perf, goldens). Disk / 9.5G free
2026-11-19T06:08:44Z SRV-28 REVIEW_CHANGES 1 major (N+1 kanji queries per word under sync lock); attempt 2; KANJI-TEXT-SHARED filed (PlainText divergence)
2026-11-19T06:09:59Z AND-28 HANDOFF (green except 4 golden tests pending SRV-28 goldens); waiting for SRV-28 merge
2026-11-19T06:14:28Z C-28d HANDOFF (claims-based pick, 40k-seed prototype 0 failures) + green; reviewer
2026-11-19T06:15:34Z C-28d REVIEW_APPROVE (+1-line lastNew clarification requested before merge)
2026-11-19T06:15:56Z SRV-28 attempt 2 CHECK_GREEN + MERGE (N+1 fixed, bounded-statement test)
2026-11-19T06:16:07Z AND-28 rebased on main (SRV-28 goldens); android check running
2026-11-19T06:17:53Z C-28d CHECK_GREEN + MERGE; CORE-28b DISPATCH writer-senior
2026-11-19T06:18:39Z AND-28 CHECK_GREEN (android, rebased on SRV-28 goldens); reviewer dispatched
2026-11-19T06:22:05Z AND-28 REVIEW_CHANGES 3 majors (suspended-started parity, kanjiKinds N+1, first-build 30k dict reads) + minors; attempt 2
2026-11-19T06:26:24Z CORE-28b HANDOFF + green (core); reviewer
2026-11-19T06:27:53Z CORE-28b REVIEW_APPROVE (8000-seed probe clean); merge check running; KANJI-TEXT-SHARED expanded (goldens for C-28d scenarios)
2026-11-19T06:28:29Z CORE-28b CHECK_GREEN + MERGE. AND-28 attempt 2 told to rebase on it
2026-11-19T06:32:07Z AND-28 attempt 2 HANDOFF (3 majors fixed w/ tests; red only on 3 missing goldens); KANJI-TEXT-SHARED DISPATCH from AND-28 branch
2026-11-19T06:40:59Z DECIDE ADR-045 (user: learnable kanji always carded; tags only furigana); C-28e DISPATCH architect; CORE-28c filed
2026-11-19T06:49:36Z KANJI-TEXT-SHARED + AND-28 CHECK_GREEN (server, android re-run by orchestrator) + MERGE
2026-11-19T06:50:52Z C-28e HANDOFF + green; reviewer
2026-11-19T06:51:39Z C-28e REVIEW_APPROVE; CORE-28c DISPATCH writer-senior on top of C-28e (merge together)
2026-11-19T07:05:30Z C-28e + CORE-28c REVIEW_APPROVE + CHECK_GREEN (core, server, android, contracts) + MERGE. Round code complete -> release prep
2026-11-19T07:06:06Z RELEASE prep: pin f108574d; building api 0.1.25, web 0.1.28, APK 0.1.35
2026-11-19T07:22:40Z RELEASE BLOCK: helm upgrade failed (OIDC token expired, needs browser login); images pushed, APK built; nothing applied to cluster
- 2026-11-19 run 34 release: helm rev 39 api 0.1.25/web 0.1.28 rolled out; CLI reinstalled; APK 0.1.35 on user 10.
2026-11-19T09:47:29Z DECIDE ADR-046 (user: rK/sK-only words → kana; tagged words require jōyō only); C-28f, CORE-28d, SCROLL-TOP filed
2026-11-19T09:47:42Z C-28f DISPATCH architect; SCROLL-TOP DISPATCH writer
2026-11-19T09:49:41Z SCROLL-TOP HANDOFF + CHECK_GREEN (web) + MERGE c87974a3 (web only)
2026-11-19T09:59:17Z C-28f HANDOFF + green (contracts); reviewer; CORE-28d DISPATCH writer-senior from t/C-28f-contracts (parallel)
2026-11-19T10:02:56Z C-28f REVIEW_APPROVE (5 minors → FOLLOWUPS; headword cache clear added to CORE-28d)
2026-11-19T10:16:39Z CORE-28d REVIEW_APPROVE (4 minors → FOLLOWUPS); orchestrator checks running
2026-11-19T10:18:27Z SCROLL-TOP visual verify PASS (playwright 412x915 + 1280x800: scrollTop 0 after Show and next card; screenshot viewed by orchestrator)
2026-11-19T10:20:54Z C-28f + CORE-28d CHECK_GREEN (core,worker,server,android,contracts) + MERGE e41c2636; release run 35 next
2026-11-19T10:21:15Z RELEASE prep run 35: pin 17eb3969; building api 0.1.26, web 0.1.29, worker 0.1.20, APK 0.1.36
2026-11-19T10:21:54Z NOTE last dict-refresh succeeded 2026-11-15 (WRK-DICT-LISTSEQ refresh never ran); run 35 needs user to POST /api/v1/dict/refresh (import scope)
2026-11-19T10:22:16Z RELEASE APK 0.1.36 built (pin 17eb3969, code 136, cert ec6b7f45 ok)
2026-11-19T10:36:40Z RELEASE run 35 DEPLOYED helm rev 40 (pin 17eb3969): api 0.1.26, web 0.1.29, worker 0.1.20; awaiting user dict-refresh + phone for APK 0.1.36
2026-11-19T10:51:27Z RELEASE APK 0.1.36 INSTALLED (user 10, versionCode 136)
2026-11-19T11:00:16Z DICT refresh OK release 5 (headword_rule ADR-046): シンガポール/かるた/いる headwords kana. Run 35 complete
2026-11-19T12:33:04Z USER: audio broken everywhere + wants replay icon (diagnosis running); WEB-DICT-DESKTOP filed + DISPATCH writer
2026-11-19T12:34:03Z DECIDE ADR-047 (audio: system-generated word audio, speaker next to word on answer, autoplay setting); C-29 DISPATCH architect
2026-11-19T12:38:52Z WEB-DICT-DESKTOP CHECK_GREEN (web) + MERGE (screenshots viewed by orchestrator: overlap fixed, two-pane); release with audio round
2026-11-19T12:49:50Z DECIDE ADR-048 (review back = undo shown revealed; dictionary round-trip keeps state); WEB-REVIEW-BACK DISPATCH writer; web 0.1.30 deploy HELD (user: bundle)
2026-11-19T12:50:00Z web 0.1.30 image pushed (pin 6c484738) but NOT deployed; next web build will supersede
2026-11-19T12:50:36Z C-29 HANDOFF + green; reviewer; CORE-29/WRK-29/SRV-29/WEB-29/AND-29 filed
2026-11-19T12:51:01Z WEB-REVIEW-BACK scope += Undo hotkey/button show undone card revealed (ADR-048)
2026-11-19T12:52:15Z C-29 REVIEW_CHANGES (1 major: resolveSpeech text items under read scope; 5 minors) -> architect attempt 2
2026-11-19T12:58:47Z C-29 attempt 2 HANDOFF; contracts+core green after rebase; server/worker/android check running before merge; CORE-29 (writer-senior) + WRK-29 (writer) DISPATCH from t/C-29-audio
2026-11-19T13:00:21Z WEB-REVIEW-BACK HANDOFF (green); screenshots looked faded -> writer asked to verify settled opacity + enabled grade buttons + grading restored card
2026-11-19T13:02:08Z WEB-REVIEW-BACK CHECK_GREEN (web) + MERGE (settled screenshots viewed; opacity/enabled asserted)
2026-11-19T13:03:22Z RELEASE APK 0.1.37 built (pin ffb37e0d, code 137, cert ok) — install when user plugs in
2026-11-19T13:03:36Z WRK-29 HANDOFF + green (worker 908); held on t/WRK-29 until C-29 merge
2026-11-19T13:06:39Z RELEASE web 0.1.31 DEPLOYED helm rev 41 (pin ffb37e0d: WEB-DICT-DESKTOP + WEB-REVIEW-BACK); api 0.1.26/worker 0.1.20 unchanged
2026-11-19T13:08:21Z C-29 alone: server/android red = expected drift (resolveSpeech route/wiring audit) -> chain merges together; CORE-29 HANDOFF green, reviewer; SRV-29 (writer-senior) + WEB-29 (writer) DISPATCH from t/CORE-29
2026-11-19T13:09:34Z CORE-29 REVIEW_APPROVE (2 minors: upper-case [sound:] → FOLLOWUPS; live has 0 [sound:] notes)
2026-11-19T13:15:19Z DECIDE ADR-047 open choices confirmed by user (kana-only, vocab-only, no listening-answer autoplay)
2026-11-19T13:18:24Z WEB-29 HANDOFF; review-back U/undo test FAILS on branch but passes on main (verified) -> regression, attempt 2
2026-11-19T13:22:28Z USER BUG: home 9 due vs review bar 23 (9 learning/0 review), done screen while reviews left after new limit, home then empty. Server 13:22: learning due 21, review due 5. Investigation DISPATCH
2026-11-19T13:30:43Z WEB-29 attempt 2: flake confirmed on main (review-back 3/4 parallel runs fail) -> accepted, WEB-PW-FLAKY filed. SRV-29 HANDOFF green -> reviewer. DECIDE ADR-049 (loop waits for today's learning; counts add up; Home refresh); C-30 DISPATCH architect
2026-11-19T13:33:35Z SRV-29 REVIEW_CHANGES (1 major: unindexed back-off scan on review path; 3 minors) -> writer attempt 2
2026-11-19T13:38:54Z SRV-29 attempt 2 HANDOFF green (major fixed as prescribed, tests added) -> accepted; AND-29 DISPATCH writer from t/SRV-29
2026-11-19T13:43:00Z C-30 HANDOFF + green; reviewer; CORE-30/SRV-30/WEB-30/AND-30/CLI-30 filed
2026-11-19T13:44:43Z C-30 REVIEW_APPROVE (4 minors: day-boundary parity + A3 text -> architect quick fix; KG-on-backlog noted in ADR-049; ship web/android with server)
2026-11-19T13:48:13Z C-30 follow-up (day-end parity, A3) done 9eff7d03; CORE-30 dispatched from t/C-30-loop-wait
2026-11-19T13:53:34Z AND-29 handed back 69fa51da; audio chain assembled on t/AUDIO-int, full make check running
2026-11-19T13:58:13Z CORE-30 0f2f1b24 approved by reviewer; held for merge with SRV-30/WEB-30/AND-30/CLI-30 after audio chain
2026-11-19T14:02:08Z audio chain (C-29,CORE-29,WRK-29,SRV-29,WEB-29,AND-29) merged ff via t/AUDIO-int 3f377a1b; all 6 module checks green
2026-11-19T14:07:08Z t/C30-int 2c901cc8 = main + CORE-30 (REVIEW-LOOP ADR-049 renumbered §15); core+contracts green. SRV-30, WEB-30 dispatched from it. Audio release pinned 3f377a1b: api 0.1.27 web 0.1.32 worker 0.1.21, APK 0.1.38 built (cert ok)
2026-11-19T14:22:51Z ADR-049 chain (C-30,CORE-30,SRV-30,WEB-30,AND-30,CLI-30) merged ff via t/C30-int 6cfb9455; all modules + cli green
2026-11-19T14:57:22Z RELEASED api 0.1.28 web 0.1.33 (pin 6cfb9455) + worker 0.1.21 (pin 3f377a1b; worker unchanged since); V4 tts_audio migrated; first tts job succeeded. APK 0.1.39 built at 6cfb9455 (cert ec6b7f45 ok), not installed; 0.1.37/0.1.38 superseded
2026-11-19T15:24:19Z APK 0.1.39 installed (user 10)
2026-11-20T04:54:20Z RELEASED api 0.1.29 (pin b9186765, PERF-1) helm rev 43; pre-deploy dump verified
2026-11-20T05:15:00Z RELEASED api 0.1.30 + web 0.1.34 (pin dd00d243, PERF-2 + UX-1) helm rev 44; pre-deploy dump verified; APK 0.1.40 pending signing env
2026-11-20T05:45:00Z RELEASED web 0.1.35 (pin 1bb9ab1b, BUG-DICTBACK: ReviewPage deriveds owned by its own effect root) helm rev 45; reproduced + verified in Chromium on a live-data copy; APK still pending signing env
2026-11-20T09:52:53Z APK 0.1.40 built (pin 8b3f81df, code 140, cert ec6b7f45 ok) + INSTALLED (users 0+10)
2026-11-20T10:00:07Z DECIDE ADR-050 (local-first shell, all routes) + ADR-051 (Keep going everywhere, count invariant) by user; architect design DISPATCH
2026-11-20T10:09:25Z architect design done: 17 tickets (C-31, LOOP-31-*, LF-*) in plan/tickets, LF-DESIGN.md; DECIDE ADR-052/053 provisional; C-31 DISPATCH
2026-11-20T10:12:17Z DECIDE ADR-052 confirmed by user + (a) due cards first in keep going (b) batch session-scoped, not owed after exit; sent to C-31
2026-11-20T10:39:53Z C-31 REVIEW_CHANGES (blocker: 422 reviewed_at drops outbox; major: updateCard/deleteCard/presets/note_type/grammar still proxy vs ADR-050) -> architect attempt 2; W4 exception added to ADR-052
2026-11-20T10:44:08Z C-31 server/android red = expected drift (parity goldens, RouteTable) -> integration branch t/LF-int
2026-11-20T10:52:59Z C-31 REVIEW_APPROVE r2, checks green -> merged into t/LF-int; DISPATCH LOOP-31-CORE-A, LF-A1, LF-A2 from t/LF-int
2026-11-20T11:13:46Z LOOP-31-CORE-A HANDOFF (fuzz: I7 fail 117 seeds, target ignored 1232/2000 before fix) -> reviewer; DISPATCH LF-A4
2026-11-20T11:15:49Z LOOP-31-CORE-A REVIEW_APPROVE + merged into t/LF-int; minors -> FOLLOWUPS
2026-11-20T11:18:15Z LF-A2 HANDOFF (4 known drift fails + 1 schema-version test outside paths -> authorized fix); DISPATCH LOOP-31-CORE-B (must add pending-answer fuzz step)
2026-11-20T11:27:53Z LF-A2 REVIEW_APPROVE (7 minors: 4 -> LF-A2b ticket, rest FOLLOWUPS/LF-X1); awaiting android re-check
2026-11-20T11:29:04Z LF-A1 HANDOFF -> reviewer; DISPATCH LOOP-31-API
2026-11-20T11:31:08Z LF-A1 REVIEW_APPROVE (minors -> LF-A1b: StoreBusy 500 -> retry/503; debounce races)
2026-11-20T11:34:48Z LF-A4 HANDOFF: bulkScheduleCards route disabled (branch predates LF-A2 ApiOperations); rebase after LF-int push, then enable + un-@Ignore test
2026-11-20T11:35:03Z DISPATCH LF-A5
2026-11-20T11:41:21Z LF-A1 + LF-A2 merged into t/LF-int (android 649 tests, only 4 known drift fails); pushed
2026-11-20T11:45:34Z LOOP-31-API + LF-A4 HANDOFF (red only on unrecorded goldens) -> merged into t/LF-int, re-check running; LF-GOLD filed; DISPATCH LF-A3
2026-11-20T11:55:17Z t/LF-int pushed with LOOP-31-API + LF-A4 (server: only ParityGoldenTest 13; android: 4 known)
2026-11-20T12:04:47Z LF-A3 HANDOFF (no new fails) -> merged into t/LF-int locally; DISPATCH LF-A6
2026-11-20T12:10:27Z t/LF-int pushed with LF-A3 (android 671 tests, 4 known fails)
2026-11-20T12:13:25Z LOOP-31-CORE-B HANDOFF (CCR additive KDoc) -> reviewer + architect; architect on fable failed 429 out of credits -> re-dispatched model=opus; DISPATCH LOOP-31-AND; LF-A3 pushed
2026-11-20T12:14:51Z LOOP-31-CORE-B REVIEW_APPROVE; web-side risks written into LOOP-31-WEB ticket; merge after CCR commit
2026-11-20T12:17:26Z LF-A5 HANDOFF (both flavors, 4 known fails) -> merged + pushed t/LF-int; DISPATCH LF-A8
2026-11-20T12:20:29Z LOOP-31-AND DONE (LOCAL_API_VERSION 3; LocalQueueLoader.load +keepGoingNewTarget outside paths, accepted) -> merged + pushed t/LF-int; DISPATCH LF-A1b
2026-11-20T12:28:23Z LF-A6 DONE (+approved DictChoice hook, top60k never overridden) -> merged + pushed t/LF-int; DISPATCH LF-A7
2026-11-20T12:37:25Z LF-A7 DONE (app-open sync -> LF-A1b); LF-A8 part 1 HANDOFF -> reviewer; LF-A8b split (confusables/grammar/media); CORE-B merged locally, core green
2026-11-20T12:37:47Z t/LF-int pushed: + LOOP-31-CORE-B (core+contracts green), LF-A7; DISPATCH LOOP-31-WEB
2026-11-20T12:38:04Z DISPATCH LF-A2b
2026-11-20T12:39:24Z LF-A8 part 1 REVIEW_APPROVE (minors -> LF-A8b notes + LF-X1)
2026-11-20T12:42:03Z LF-A8 part 1 merged + pushed t/LF-int (android 714 tests, 3 known fails)
2026-11-20T14:22:35Z - RESUME run 37: LF-A1b, LF-A2b, LOOP-31-WEB writers dead (uncommitted work) -> re-dispatched in same worktrees (opus); no fable agents per user
2026-11-20T14:22:35Z LF-A8b DISPATCH writer-senior (opus) from origin/t/LF-int
2026-11-20T14:30:16Z - RESUME run 38: run 37 writers dead again (uncommitted); lfint worktree recreated; LF-A1b, LF-A2b, LF-A8b, LOOP-31-WEB re-dispatched in place
2026-11-20T14:35:47Z LOOP-31-WEB HANDOFF; check: only 3 web shell tests red, same 3 red on t/LF-int base (known drift) -> reviewer
2026-11-20T14:36:13Z LF-A2b HANDOFF (3 known fails); item 4 half-fixed -> authorized ShellModule.kt fallback edit, writer resumed
2026-11-20T14:37:34Z LF-A1b HANDOFF (3 known fails) -> merged + pushed t/LF-int ed3062a5
2026-11-20T14:37:34Z LF-W0 DISPATCH writer (new ticket: 3 pre-existing web shell test fails on LF-int)
2026-11-20T14:39:26Z LF-W0 HANDOFF (tests stale vs C-31, no code change); web green -> merged + pushed t/LF-int
2026-11-20T14:39:44Z LOOP-31-WEB REVIEW_CHANGES (2 major: inert stale-response test, C5 retry bursts; 6 minor) -> writer attempt 2; minor 5 -> FOLLOWUPS
2026-11-20T14:40:02Z LF-A2b HANDOFF r2 (ShellModule fallback added; 3 known fails) -> merged locally into t/LF-int 61fad693, android re-check running
2026-11-20T14:41:13Z LOOP-31-WEB HANDOFF attempt 2; web green 321/321 -> reviewer re-review
2026-11-20T14:41:53Z LOOP-31-WEB REVIEW_APPROVE r2 (2 test minors -> LF-W1 ticket) -> merged locally into t/LF-int; DISPATCH LF-W1
2026-11-20T14:43:07Z t/LF-int pushed a17988c0: + LF-A2b, LOOP-31-WEB (android 738 tests, 3 known fails; web green)
2026-11-20T14:49:16Z - PAUSE requested by user: no new dispatches; let LF-A8b + LF-W1 finish
2026-11-20T14:49:42Z LF-A8b HANDOFF CONTRACT_CHANGE_REQUEST (media deck deck.deleted rejected by server) -> BLOCKED_CONTRACT; C-32 filed, not dispatched (paused)
2026-11-20T14:54:17Z LF-W1 HANDOFF; web green 344/344 -> READY_FOR_REVIEW (review held: paused). Logout->clearOutbox wiring gap noted. Run paused, no agents running.
