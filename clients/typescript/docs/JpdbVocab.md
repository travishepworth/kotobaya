
# JpdbVocab

A jpdb vocabulary item that the automatic mapper could not map (`app.jpdb_vocab`, C-11).

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`firstSeenAt` | Date
`jpdbVid` | number
`media` | [Array&lt;JpdbVocabMedia&gt;](JpdbVocabMedia.md)
`occurrenceTotal` | number
`reading` | string
`resolution` | [JpdbVocabResolution](JpdbVocabResolution.md)
`resolvedAt` | Date
`spelling` | string

## Example

```typescript
import type { JpdbVocab } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "firstSeenAt": null,
  "jpdbVid": null,
  "media": null,
  "occurrenceTotal": null,
  "reading": null,
  "resolution": null,
  "resolvedAt": null,
  "spelling": null,
} satisfies JpdbVocab

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JpdbVocab
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


