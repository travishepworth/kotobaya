# HANDOFF C-2

STATUS: DONE
BRANCH: t/C-2-freeze-fixes
CHECK: contracts/{db,sync,api,core}/check.sh, `make check MODULE=core`, `make check MODULE=web` → green

## Changed (≤ 6 lines)
- A1/A3: SYNC 5.2 "Parsing the body" covers duplicate keys, and the 5.3 order now has a dup-key row and `insufficient_scope`. Mirrored in batch.schema, openapi push, Json.kt/OpCodec KDoc.
- A2/A4/A5: SCHEMA 5.3 and Local.sq match SYNC (purge rule, Retry/Discard/Re-mint, all sync_state keys; SYNC wins). §7 now points to SYNC §14. job.kind comment uses JobKind.
- B1: new SYNC 6.3. server_state gets `log_epoch`, `durable_seq` and `restore_pending`. Backup job, restore script and server startup steps are defined (SCHEMA 4.4, V1 comment).
- B2/B3: jpdb kanji arrays, full grade table, new result buckets with 3 sums. `KanjiData`/`JsKanji` (default null), `QueueItem.kanji`.
- B4/B5/B8/B10/A6: dict search wording; Search.kt evaluation inputs + prefilter/residual; blobs URL; vocab types need expression/reading/meaning; add_tags >200 → 422 out_of_range.
- "C-2 changes" in all 4 SUMMARYs.

## Acceptance → tests
- dup key inside an op → contracts/sync/examples/raw-push/*, checked by tools/validate.py

## New dependencies
- none

## For the reviewer (≤ 3 lines)
- My calls: kanji `known`-only rows still get a note and card; vocab `known` then reviews gets both a status and a card; `review_type=scheduled` for imports.
- New rejections (vocab field keys; add_tags 422) tighten validation. OK only because nothing has shipped yet.
- Startup order: restore finalization, then `core_scheduler_version`.
