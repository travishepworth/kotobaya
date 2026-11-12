
# GrammarSpan

A grammar span (`app.sentence_grammar_span`).

## Properties

Name | Type
------------ | -------------
`createdAt` | Date
`endOffset` | number
`grammarPointId` | string
`mediaId` | string
`origin` | [SpanOrigin](SpanOrigin.md)
`ruleIndex` | number
`sentenceId` | string
`startOffset` | number

## Example

```typescript
import type { GrammarSpan } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "createdAt": null,
  "endOffset": null,
  "grammarPointId": null,
  "mediaId": null,
  "origin": null,
  "ruleIndex": null,
  "sentenceId": null,
  "startOffset": null,
} satisfies GrammarSpan

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarSpan
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


