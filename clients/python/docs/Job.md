# Job

A background job (`app.job`). `payload` and `result` depend on `kind`:  | `kind` | `payload` schema | `result` schema (when `succeeded`) | |---|---|---| | `dict-refresh` | `JobPayloadDictRefresh` | `JobResultDictRefresh` | | `media-import` | `MediaImportRequest` (with `media_id`) | `JobResultMedia` | | `media-parse` | `JobPayloadMediaParse` | `JobResultMedia` | | `jpdb-deck-import` | `JpdbDeckImportRequest` (with `media_id`; `remap_only` for correction re-publishes) | `JobResultMedia` | | `jpdb-history-import` | `JpdbHistoryImportRequest` | `JobResultJpdbHistory` | | `fsrs-optimize` | `JobPayloadFsrsOptimize` | `JobResultFsrsOptimize` | | `tts` | `TtsRequest` | `JobResultTts` | | `deck-build` | `JobPayloadDeckBuild` | `JobResultDeckBuild` | | `grammar-detect` | `JobPayloadGrammarDetect` | `JobResultGrammarDetect` |  **`grammar-detect` (C-13).** Re-runs grammar detection over stored tokens; never re-parses. Not started through the API: the server enqueues a fan-out (`{}`) when a `detection_rules` register changes, and the fan-out enqueues one per-media job (`{\"media_id\"}`) per eligible media. A per-media job that finds new spans republishes the media through `media-publish` (a new `content_version`, same tokens, `parse_status` unchanged). It is an ordinary public job (listed, streamed, cancellable) but is never forwarded to ntfy. Normative protocol: `contracts/db/SCHEMA.md`, \"Jobs\", \"`grammar-detect`\".  **Server-internal jobs (C-9).** An `app.job` row whose `kind` is not a `JobKind` value is *server-internal*: a hand-off between the worker and the server inside one public job (its *parent*). The registered internal kinds are listed in `contracts/db/SCHEMA.md` (\"Jobs\"): `jpdb-history-apply` (see `importJpdbHistory`, \"Execution\") and `media-publish` (C-11; parents `media-import`, `media-parse`, `jpdb-deck-import`, and `grammar-detect` (C-13); `contracts/db/MEDIA_PIPELINE.md` 3), and `fsrs-params-publish` (C-16; parent `fsrs-optimize`; `contracts/db/FSRS_PUBLISH.md`). Rules: - The API never exposes an internal row: `listJobs` skips it (also for `limit` and   cursor arithmetic: pages are built from public rows only), `getJob` and `cancelJob`   answer `404 not_found` for its id, and `streamEvents` never sends a `job.updated`   (nor an ntfy push) whose job is internal. It is visible only through its parent:   the parent's `status`, `progress`, `progress_message`, `result` and `error`   reflect it as the parent kind documents. - An internal kind is never added to `JobKind` and never named like one; a new one is   added to the SCHEMA.md list by a contract ticket (additive: no column or `CHECK`   changes, `app.job.kind` already accepts any slug). - The worker never claims an internal kind (it claims only kinds it registered); the   server's runner for that kind is the only claimer. Cancelling the parent cancels   the child (the child's runner checks the parent before work and between batches). 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**attempts** | **int** | Attempts started so far. | 
**created_at** | **datetime** | When it was queued. | 
**error** | **str** | Error text of the last failed attempt. | 
**finished_at** | **datetime** | Set iff the status is terminal. | 
**id** | **str** | Job id (server-generated UUIDv7). | 
**kind** | [**JobKind**](JobKind.md) |  | 
**max_attempts** | **int** | Attempts allowed before the job is &#x60;failed&#x60;. | 
**payload** | **Dict[str, object]** | Input of the job; schema by &#x60;kind&#x60;. | 
**progress** | **float** | Fraction done; 1 when succeeded. | 
**progress_message** | **str** | Short human-readable stage, e.g. &#x60;parsing ep02.srt (2/12)&#x60;. | 
**result** | **Dict[str, object]** | Output when &#x60;succeeded&#x60; (schema by &#x60;kind&#x60;); may hold partial counts when &#x60;failed&#x60; or &#x60;cancelled&#x60;; null before. | 
**started_at** | **datetime** | Start of the current or last attempt. | 
**status** | [**JobStatus**](JobStatus.md) |  | 

## Example

```python
from kotobaya_client.models.job import Job

# TODO update the JSON string below
json = "{}"
# create an instance of Job from a JSON string
job_instance = Job.from_json(json)
# print the JSON string representation of the object
print(Job.to_json())

# convert the object into a dict
job_dict = job_instance.to_dict()
# create an instance of Job from a dict
job_from_dict = Job.from_dict(job_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


