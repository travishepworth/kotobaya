
# ShellEventSyncChanged

Payload of `sync.changed` in shell mode - local learning data changed; refetch what is shown.

## Properties

Name | Type
------------ | -------------
`highWaterSeq` | number
`pendingOps` | number

## Example

```typescript
import type { ShellEventSyncChanged } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "highWaterSeq": null,
  "pendingOps": null,
} satisfies ShellEventSyncChanged

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellEventSyncChanged
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


