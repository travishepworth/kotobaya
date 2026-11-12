
# GrammarSpanRemovedSchema

Removes a manual grammar highlight (reversible tombstone). OPS.md#grammarspan_removed

## Properties

Name | Type
------------ | -------------
`endOffset` | number
`grammarPointId` | string
`sentenceId` | string
`startOffset` | number

## Example

```typescript
import type { GrammarSpanRemovedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "endOffset": null,
  "grammarPointId": null,
  "sentenceId": null,
  "startOffset": null,
} satisfies GrammarSpanRemovedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as GrammarSpanRemovedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


