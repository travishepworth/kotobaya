
# JobResultFsrsOptimize

Result of an optimizer run (`contracts/db/FSRS_PUBLISH.md`). `log_loss_before`, `holdout_review_count` and `reason` were added by C-16 and are not required: results stored before it lack them (treat absent as null). 

## Properties

Name | Type
------------ | -------------
`holdoutReviewCount` | number
`logLoss` | number
`logLossBefore` | number
`presetId` | string
`published` | boolean
`reason` | string
`reviewCount` | number
`rmseBins` | number
`weightsVersion` | number

## Example

```typescript
import type { JobResultFsrsOptimize } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "holdoutReviewCount": null,
  "logLoss": null,
  "logLossBefore": null,
  "presetId": null,
  "published": null,
  "reason": null,
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


