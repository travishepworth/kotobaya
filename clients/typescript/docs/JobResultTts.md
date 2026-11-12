
# JobResultTts

Result of a TTS job.

## Properties

Name | Type
------------ | -------------
`blobHash` | string
`contentType` | string
`durationMs` | number

## Example

```typescript
import type { JobResultTts } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "blobHash": null,
  "contentType": null,
  "durationMs": null,
} satisfies JobResultTts

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultTts
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


