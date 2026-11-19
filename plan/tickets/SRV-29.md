# SRV-29 — Server: word_audio, resolveSpeech, prefetch sweep (C-29)

- Phase: P10 · Model: opus (writer-senior) · Risk: high · Deps: C-29, CORE-29
- Branch: t/SRV-29 · Owned: server/**, fixtures/shell-parity/golden/**
- CI: server

## Read
- docs/DECISIONS.md ADR-047; contracts/db/SCHEMA.md 6.6 (read FIRST); contracts/api/SUMMARY.md "C-29 changes" (row SRV-29); plan/handoffs/C-29.md
- contracts/api/openapi.yaml resolveSpeech, QueueItem.word_audio; core/src/commonMain/kotlin/kotobaya/contracts/render/WordAudio.kt; docs/REVIEW-LOOP.md §14

## Do
Per the C-29 SUMMARY row: V4 migration; word_audio on queue + drill items with urgent enqueue; resolveSpeech (scope test); 10-min sweep for cards due within audio.prefetch_days (cap 300 waiting jobs); settings validation (review.autoplay_audio, tts.voice, audio.prefetch_days 0-365); synthesizeSpeech default voice via core; re-record parity goldens that call getReviewQueue/createDrillSession.

## Acceptance
- [ ] tests: resolveSpeech states ready/pending/failed/none, scope enforcement, sweep cap, settings validation; make check MODULE=server green.
