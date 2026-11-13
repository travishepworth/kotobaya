
# JpdbVocabResolutionPut

A correction. `entry_seq` is required for `mapped` and forbidden for `ignored` (`422`).

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`resolution` | string

## Example

```typescript
import type { JpdbVocabResolutionPut } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "resolution": null,
} satisfies JpdbVocabResolutionPut

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JpdbVocabResolutionPut
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


