
# PullResponse

Body of GET /sync/pull?since=<seq>.

## Properties

Name | Type
------------ | -------------
`durableSeq` | number
`hasMore` | boolean
`highWaterSeq` | number
`logEpoch` | string
`nextSince` | number
`ops` | [Array&lt;OpSchema&gt;](OpSchema.md)
`serverTime` | number

## Example

```typescript
import type { PullResponse } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "durableSeq": null,
  "hasMore": null,
  "highWaterSeq": null,
  "logEpoch": null,
  "nextSince": null,
  "ops": null,
  "serverTime": null,
} satisfies PullResponse

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as PullResponse
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


