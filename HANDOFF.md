# HANDOFF T411b

STATUS: DONE
BRANCH: t/T411b-media-api
CHECK: targeted green (`--tests kotobaya.media.api.*`, 6 tests); full `make check MODULE=server` did not finish in my time budget (host contention; known OOM F-11), not confirmed

## Changed (≤ 6 lines)
- `server/.../media/api/MediaApiModule.kt`: `MediaApiModule(db, opApply)` handlers for listMedia, getMedia, updateMedia, deleteMedia, importMedia, importJpdbDeck, reparseMedia, listJpdbVocab, putJpdbVocabResolution, clearJpdbVocabResolution.
- `server/.../media/api/MediaApiTest.kt`: 6 tests.
- `OpApplier.mediaDeleted` already hard-deletes content and emits `deck.deleted`; not duplicated.

## Acceptance → tests
- importMedia retry / 409 / missing blob / wrong source (+ job survives prune) → `importMediaRetryConcurrentMissingBlobAndWrongSource`
- importJpdbDeck reuse + 200 dedupe → `importJpdbDeckReusesLiveMediaAndDedupes`
- deleteMedia → `deleteMediaEmitsOpsAndLeavesContentGoneButManualSpansAndNotes`
- reparse jpdb 409 → `reparseJpdbIs409AndFileMediaEmitsParsingOnce`
- mapping put/delete/list → `mappingCorrectionsPutListClear`

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- Mapping handlers run inside `opApply.rest` (takes 7033001), then 7033011 first thing, per MEDIA_PIPELINE 3.4/5.4. `parsing` is emitted via a shared helper only when a job row was inserted and status is not already parsing.
- Choices the contract left open: nonexistent `media_id` on append → 404, deleted → 422; `import_id` retry is answered before body validation; `job.updated` notify sent for jobs cancelled by deleteMedia (also cancels internal `media-publish` rows). Not wired (W-5).
