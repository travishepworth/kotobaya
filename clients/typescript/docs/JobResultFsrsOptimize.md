
# JobResultFsrsOptimize

Result of an optimizer run.

## Properties

Name | Type
------------ | -------------
`logLoss` | number
`presetId` | string
`published` | boolean
`reviewCount` | number
`rmseBins` | number
`weightsVersion` | number

## Example

```typescript
import type { JobResultFsrsOptimize } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "logLoss": null,
  "presetId": null,
  "published": null,
  "reviewCount": null,
  "rmseBins": null,
  "weightsVersion": null,
} satisfies JobResultFsrsOptimize

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultFsrsOptimize
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


