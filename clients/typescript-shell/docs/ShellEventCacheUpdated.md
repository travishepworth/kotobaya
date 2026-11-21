
# ShellEventCacheUpdated

C-31. Payload of `cache.updated`: a background fetch of one operation of class `cached` changed what the next request of the same cache key will get (`SHELL.md` rules K-2, K-3). Sent only when the stored body changed (a first body counts), when a stored entry was deleted because the server answered `404` / `410`, or when a miss ended in a final refusal. Never for a revalidation that found the same bytes. The SPA requests the operation again if it shows it; a duplicate or unmatched event is ignored. At most one per key per second. Example: `{operation_id: getDeckCoverage, path: /api/v1/decks/01931f40-2000-7000-8000-000000000d01/coverage, query: \'\', status: 200}`. 

## Properties

Name | Type
------------ | -------------
`operationId` | string
`path` | string
`query` | string
`status` | number

## Example

```typescript
import type { ShellEventCacheUpdated } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "operationId": null,
  "path": null,
  "query": null,
  "status": null,
} satisfies ShellEventCacheUpdated

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellEventCacheUpdated
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


