
# JpdbHistoryImportRequest

jpdb export to import. Also the payload of `jpdb-history-import` jobs.

## Properties

Name | Type
------------ | -------------
`blobHash` | string
`importReviews` | boolean
`importWordStatus` | boolean

## Example

```typescript
import type { JpdbHistoryImportRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "blobHash": null,
  "importReviews": null,
  "importWordStatus": null,
} satisfies JpdbHistoryImportRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JpdbHistoryImportRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


