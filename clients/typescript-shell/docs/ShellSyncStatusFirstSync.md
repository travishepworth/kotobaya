
# ShellSyncStatusFirstSync

C-31 (ADR-050). The first complete pull of this pairing: the one time the SPA shows a full-screen \"Setting up\" state and the user waits for the network (`SHELL.md` 10.18). Always present (LOCAL_API_VERSION 3). While `done` is false, `getReviewQueue` does not materialize deck entries and the background work that follows the first sync (response-cache warm pass, dictionary auto-install, audio prefetch) has not started. Example before the first response: `{done: false, applied: 0, total: null}`; midway: `{done: false, applied: 12000, total: 48213}`; afterwards: `{done: true, applied: 48213, total: 48213}`. 

## Properties

Name | Type
------------ | -------------
`done` | boolean
`applied` | number
`total` | number

## Example

```typescript
import type { ShellSyncStatusFirstSync } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "done": null,
  "applied": null,
  "total": null,
} satisfies ShellSyncStatusFirstSync

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellSyncStatusFirstSync
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


