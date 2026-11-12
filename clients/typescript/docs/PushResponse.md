
# PushResponse

Body of a successful POST /sync/push response.

## Properties

Name | Type
------------ | -------------
`durableSeq` | number
`highWaterSeq` | number
`logEpoch` | string
`results` | [Array&lt;PushResult&gt;](PushResult.md)
`serverTime` | number

## Example

```typescript
import type { PushResponse } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "durableSeq": null,
  "highWaterSeq": null,
  "logEpoch": null,
  "results": null,
  "serverTime": null,
} satisfies PushResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PushResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


