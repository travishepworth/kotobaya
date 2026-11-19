# WRK-29 — Worker: tts job uses app.tts_audio cache (C-29)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: C-29
- Branch: t/WRK-29 · Owned: worker/**
- CI: worker

## Read
- docs/DECISIONS.md ADR-047; contracts/db/SCHEMA.md 6.6 (read FIRST); contracts/api/SUMMARY.md "C-29 changes" (row WRK-29); plan/handoffs/C-29.md
- contracts/api/openapi.yaml resolveSpeech, QueueItem.word_audio; core/src/commonMain/kotlin/kotobaya/contracts/render/WordAudio.kt; docs/REVIEW-LOOP.md §14

## Do
Per the C-29 SUMMARY row: tts job cache lookup/upsert on app.tts_audio (replaces job-history lookup), voice/length validation; add V4 to worker/tests/dict/conftest.py migration list.

## Acceptance
- [ ] run-twice idempotency test; make check MODULE=worker green.
