
# GrammarSpanRef

A grammar span inside a sentence.

## Properties

Name | Type
------------ | -------------
`endOffset` | number
`grammarPointId` | string
`origin` | [SpanOrigin](SpanOrigin.md)
`startOffset` | number

## Example

```typescript
import type { GrammarSpanRef } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "endOffset": null,
  "grammarPointId": null,
  "origin": null,
  "startOffset": null,
} satisfies GrammarSpanRef

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarSpanRef
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


