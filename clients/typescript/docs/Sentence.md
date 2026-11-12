
# Sentence

A sentence of imported media (`app.sentence`) with its tokens and live grammar spans.

## Properties

Name | Type
------------ | -------------
`audioBlobHash` | string
`endMs` | number
`grammarSpans` | [Array&lt;GrammarSpanRef&gt;](GrammarSpanRef.md)
`id` | string
`imageBlobHash` | string
`location` | { [key: string]: any; }
`mediaId` | string
`ordinal` | number
`origin` | string
`segmentId` | string
`startMs` | number
`text` | string
`tokens` | [Array&lt;SentenceToken&gt;](SentenceToken.md)
`unknownCount` | number

## Example

```typescript
import type { Sentence } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "audioBlobHash": null,
  "endMs": null,
  "grammarSpans": null,
  "id": null,
  "imageBlobHash": null,
  "location": null,
  "mediaId": null,
  "ordinal": null,
  "origin": null,
  "segmentId": null,
  "startMs": null,
  "text": null,
  "tokens": null,
  "unknownCount": null,
} satisfies Sentence

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Sentence
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


