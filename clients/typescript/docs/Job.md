
# Job

A background job (`app.job`). `payload` and `result` depend on `kind`:  | `kind` | `payload` schema | `result` schema (when `succeeded`) | |---|---|---| | `dict-refresh` | `JobPayloadDictRefresh` | `JobResultDictRefresh` | | `media-import` | `MediaImportRequest` (with `media_id`) | `JobResultMedia` | | `media-parse` | `JobPayloadMediaParse` | `JobResultMedia` | | `jpdb-deck-import` | `JpdbDeckImportRequest` (with `media_id`; `remap_only` for correction re-publishes) | `JobResultMedia` | | `jpdb-history-import` | `JpdbHistoryImportRequest` | `JobResultJpdbHistory` | | `fsrs-optimize` | `JobPayloadFsrsOptimize` | `JobResultFsrsOptimize` | | `tts` | `TtsRequest` | `JobResultTts` | | `deck-build` | `JobPayloadDeckBuild` | `JobResultDeckBuild` | | `grammar-detect` | `JobPayloadGrammarDetect` | `JobResultGrammarDetect` |  **`grammar-detect` (C-13).** Re-runs grammar detection over stored tokens; never re-parses. Not started through the API: the server enqueues a fan-out (`{}`) when a `detection_rules` register changes, and the fan-out enqueues one per-media job (`{\"media_id\"}`) per eligible media. A per-media job that finds new spans republishes the media through `media-publish` (a new `content_version`, same tokens, `parse_status` unchanged). It is an ordinary public job (listed, streamed, cancellable) but is never forwarded to ntfy. Normative protocol: `contracts/db/SCHEMA.md`, \"Jobs\", \"`grammar-detect`\".  **`deck-build` (C-21).** Builds the deck-item snapshot of one `list` deck from the current dictionary release (a JLPT vocabulary list or a frequency band) and publishes it through the internal `deck-publish` child. Enqueued by `createDeck` (type `list`) and, for every live list deck, by each `dict-refresh` that builds a new release. Fails with `empty_list` rather than publish an empty deck; `unknown_list`, `unknown_deck`, `deck_deleted`, `no_release` likewise. A rebuild with identical items publishes nothing (`changed: false`). Normative protocol: `contracts/db/VOCAB_LISTS.md` 4.4-5.  **Server-internal jobs (C-9).** An `app.job` row whose `kind` is not a `JobKind` value is *server-internal*: a hand-off between the worker and the server inside one public job (its *parent*). The registered internal kinds are listed in `contracts/db/SCHEMA.md` (\"Jobs\"): `jpdb-history-apply` (see `importJpdbHistory`, \"Execution\") and `media-publish` (C-11; parents `media-import`, `media-parse`, `jpdb-deck-import`, and `grammar-detect` (C-13); `contracts/db/MEDIA_PIPELINE.md` 3), `fsrs-params-publish` (C-16; parent `fsrs-optimize`; `contracts/db/FSRS_PUBLISH.md`), and `deck-publish` (C-21; parent `deck-build`; `contracts/db/VOCAB_LISTS.md` 5). Rules: - The API never exposes an internal row: `listJobs` skips it (also for `limit` and   cursor arithmetic: pages are built from public rows only), `getJob` and `cancelJob`   answer `404 not_found` for its id, and `streamEvents` never sends a `job.updated`   (nor an ntfy push) whose job is internal. It is visible only through its parent:   the parent\'s `status`, `progress`, `progress_message`, `result` and `error`   reflect it as the parent kind documents. - An internal kind is never added to `JobKind` and never named like one; a new one is   added to the SCHEMA.md list by a contract ticket (additive: no column or `CHECK`   changes, `app.job.kind` already accepts any slug). - The worker never claims an internal kind (it claims only kinds it registered); the   server\'s runner for that kind is the only claimer. Cancelling the parent cancels   the child (the child\'s runner checks the parent before work and between batches). 

## Properties

Name | Type
------------ | -------------
`attempts` | number
`createdAt` | Date
`error` | string
`finishedAt` | Date
`id` | string
`kind` | [JobKind](JobKind.md)
`maxAttempts` | number
`payload` | { [key: string]: any; }
`progress` | number
`progressMessage` | string
`result` | { [key: string]: any; }
`startedAt` | Date
`status` | [JobStatus](JobStatus.md)

## Example

```typescript
import type { Job } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "attempts": null,
  "createdAt": null,
  "error": null,
  "finishedAt": null,
  "id": null,
  "kind": null,
  "maxAttempts": null,
  "payload": null,
  "progress": null,
  "progressMessage": null,
  "result": null,
  "startedAt": null,
  "status": null,
} satisfies Job

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Job
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


