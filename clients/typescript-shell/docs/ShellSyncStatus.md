
# ShellSyncStatus

Sync state of this device. Also the payload of the `shell.sync` event.

## Properties

Name | Type
------------ | -------------
`phase` | string
`paired` | boolean
`online` | boolean
`pendingOps` | number
`rejectedOps` | number
`lastSyncAt` | Date
`stale` | boolean
`pullSeq` | number
`clockWarning` | boolean
`mintingBlocked` | boolean
`progress` | [ShellSyncStatusProgress](ShellSyncStatusProgress.md)
`lastError` | string
`consecutiveFailures` | number
`firstSync` | [ShellSyncStatusFirstSync](ShellSyncStatusFirstSync.md)

## Example

```typescript
import type { ShellSyncStatus } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "phase": null,
  "paired": null,
  "online": null,
  "pendingOps": null,
  "rejectedOps": null,
  "lastSyncAt": null,
  "stale": null,
  "pullSeq": null,
  "clockWarning": null,
  "mintingBlocked": null,
  "progress": null,
  "lastError": null,
  "consecutiveFailures": null,
  "firstSync": null,
} satisfies ShellSyncStatus

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellSyncStatus
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


