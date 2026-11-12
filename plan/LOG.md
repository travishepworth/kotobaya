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
