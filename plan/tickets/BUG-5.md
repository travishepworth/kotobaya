# BUG-5 — GET /api/v1/kanji/hot returns 501; audit remaining 501s; SSE channel-write noise

- Phase: P7 (live bugfix) · Model: sonnet · Risk: med
- Deps: none (server; do not touch stats/)
- Branch: t/BUG-5-kanji-hot
- Owned paths: `server/src/main/kotlin/kotobaya/struggle/`, `server/src/main/kotlin/kotobaya/jobs/` (SSE only), `server/src/main/kotlin/kotobaya/server/` (Main.kt wiring + routing only), `server/src/test/kotlin/kotobaya/` (struggle, jobs/SSE, WiringAuditTest)
- Module(s) for CI: server

## Read
- Contracts: `contracts/api/openapi.yaml` operation `listHotKanji` (and `listHotWords` for the sibling impl); SPEC struggle tools section
- Existing: listHotWords implementation (T501 core scorer), WiringAuditTest 501 allowlist

## Do
1. Implement listHotKanji per contract, reusing the T501 core hot scorer (kanji variant if core has it; if core
   lacks a kanji scorer, aggregate hot words by kanji per the contract description — if the contract is
   ambiguous, pick the simplest reading and note it). Remove it from the 501 allowlist.
2. Audit: list every operation still 501 (WiringAuditTest allowlist) and grep `web/src` and `android/` for
   calls to each. Report in HANDOFF which still-501 ops are called by web/Android (do not implement them
   beyond kanji/hot unless trivial wiring of an existing handler).
3. Minor: ChannelWriteException "Cannot write to a channel" in the API log. If it's from SSE/streaming
   clients disconnecting, catch it in the stream writer and log at debug. Don't swallow other exceptions.

## Acceptance
- [ ] Test: listHotKanji returns ranked kanji for a seeded review history; respects contract params/limits; 401 without auth.
- [ ] WiringAuditTest updated (allowlist shrinks).
- [ ] Test or clear reasoning for the disconnect handling (a test closing the SSE client mid-stream logs no ERROR if feasible).
- [ ] make check MODULE=server green.
