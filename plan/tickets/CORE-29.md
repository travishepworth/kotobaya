# CORE-29 — Core: DefaultWordAudioResolver + renderer word audio (C-29)

- Phase: P10 · Model: opus (writer-senior) · Risk: high · Deps: C-29
- Branch: t/CORE-29 · Owned: core/** except core/src/commonMain/kotlin/kotobaya/contracts/**
- CI: core

## Read
- docs/DECISIONS.md ADR-047; contracts/db/SCHEMA.md 6.6 (read FIRST); contracts/api/SUMMARY.md "C-29 changes" (row CORE-29); plan/handoffs/C-29.md
- contracts/api/openapi.yaml resolveSpeech, QueueItem.word_audio; core/src/commonMain/kotlin/kotobaya/contracts/render/WordAudio.kt; docs/REVIEW-LOOP.md §14

## Do
Per the C-29 SUMMARY row: DefaultWordAudioResolver (jvm + js tests from the WordAudio.kt table); renderer block W, {{audio:word}}, RenderedCard.wordAudio/autoplayAudio, JsRenderInput.wordAudio + JsRenderedCard members; built-in vocab templates (speaker inside .kb-expression on the answer side; listening = front); update render goldens.

## Acceptance
- [ ] resolver table tests (jvm+js); render goldens updated; make check MODULE=core green.
