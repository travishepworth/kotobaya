
# GrammarSpanPutSchema

Adds (or re-adds) a manual grammar highlight. Whole-row last-writer-wins on hlc together with grammar.span_removed. OPS.md#grammarspan_put

## Properties

Name | Type
------------ | -------------
`endOffset` | number
`grammarPointId` | string
`mediaId` | string
`sentenceId` | string
`startOffset` | number

## Example

```typescript
import type { GrammarSpanPutSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "endOffset": null,
  "grammarPointId": null,
  "mediaId": null,
  "sentenceId": null,
  "startOffset": null,
} satisfies GrammarSpanPutSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarSpanPutSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


