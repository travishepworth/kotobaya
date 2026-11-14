
# JobResultDictRefresh

Result of a dictionary refresh.

## Properties

Name | Type
------------ | -------------
`entries` | number
`releaseId` | number
`retired` | number
`version` | string
`vocabLists` | [Array&lt;VocabListReport&gt;](VocabListReport.md)

## Example

```typescript
import type { JobResultDictRefresh } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entries": null,
  "releaseId": null,
  "retired": null,
  "version": null,
  "vocabLists": null,
} satisfies JobResultDictRefresh

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultDictRefresh
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


