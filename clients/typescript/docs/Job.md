
# Job

A background job (`app.job`). `payload` and `result` depend on `kind`:  | `kind` | `payload` schema | `result` schema (when `succeeded`) | |---|---|---| | `dict-refresh` | `JobPayloadDictRefresh` | `JobResultDictRefresh` | | `media-import` | `MediaImportRequest` (with `media_id`) | `JobResultMedia` | | `media-parse` | `JobPayloadMediaParse` | `JobResultMedia` | | `jpdb-deck-import` | `JpdbDeckImportRequest` (with `media_id`) | `JobResultMedia` | | `jpdb-history-import` | `JpdbHistoryImportRequest` | `JobResultJpdbHistory` | | `fsrs-optimize` | `JobPayloadFsrsOptimize` | `JobResultFsrsOptimize` | | `tts` | `TtsRequest` | `JobResultTts` | | `deck-build` | `JobPayloadDeckBuild` | `JobResultDeckBuild` | 

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


