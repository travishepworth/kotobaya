# WEB-29 — Web: word audio on review (speaker, autoplay, P, resolve-and-rerender) (C-29)

- Phase: P10 · Model: sonnet (writer) · Risk: med · Deps: C-29, CORE-29, WEB-REVIEW-BACK (same review page)
- Branch: t/WEB-29 · Owned: web/src/**, assets/card/**, web/playwright/**
- CI: web

## Read
- docs/DECISIONS.md ADR-047; contracts/db/SCHEMA.md 6.6 (read FIRST); contracts/api/SUMMARY.md "C-29 changes" (row WEB-29); plan/handoffs/C-29.md
- contracts/api/openapi.yaml resolveSpeech, QueueItem.word_audio; core/src/commonMain/kotlin/kotobaya/contracts/render/WordAudio.kt; docs/REVIEW-LOOP.md §14

## Do
Per the C-29 SUMMARY row and REVIEW-LOOP §14: pass word_audio.blob_hash to the renderer; autoplay on reveal (setting review.autoplay_audio), P / tap replay, resolve pending audio and re-render; settings toggle (i18n); CSS .kb-expression .kb-audio (speaker beside the word, in normal flow, no overlap).

## Acceptance
- [ ] Playwright layout/no-overlap test + screenshots (412x915, 1280x800) viewed; make check MODULE=web green.
