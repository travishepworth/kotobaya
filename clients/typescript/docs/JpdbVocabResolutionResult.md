
# JpdbVocabResolutionResult

Outcome of setting or clearing a correction.

## Properties

Name | Type
------------ | -------------
`jobs` | [Array&lt;Job&gt;](Job.md)
`vocab` | [JpdbVocab](JpdbVocab.md)

## Example

```typescript
import type { JpdbVocabResolutionResult } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "jobs": null,
  "vocab": null,
} satisfies JpdbVocabResolutionResult

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JpdbVocabResolutionResult
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


