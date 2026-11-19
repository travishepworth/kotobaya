# AND-29 — Android: local word_audio + offline audio prefetch (C-29)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: C-29, CORE-29, SRV-29
- Branch: t/AND-29 · Owned: android/**
- CI: android

## Read
- docs/DECISIONS.md ADR-047; contracts/db/SCHEMA.md 6.6 (read FIRST); contracts/api/SUMMARY.md "C-29 changes" (row AND-29); plan/handoffs/C-29.md
- contracts/api/openapi.yaml resolveSpeech, QueueItem.word_audio; core/src/commonMain/kotlin/kotobaya/contracts/render/WordAudio.kt; docs/REVIEW-LOOP.md §14

## Do
Per the C-29 SUMMARY row: device table tts_audio (schema migration); local queue word_audio; prefetch pass (due window + today's new) resolving via resolveSpeech and downloading blobs into the blob cache; local settings validation; parity goldens of SRV-29. Ensure WebView autoplay allowed (mediaPlaybackRequiresUserGesture=false) in the shell.

## Acceptance
- [ ] tests: migration, local word_audio parity, prefetch picks the right pairs; make check MODULE=android green.
