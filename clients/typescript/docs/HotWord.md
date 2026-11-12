
# HotWord

A word you are currently struggling with.

## Properties

Name | Type
------------ | -------------
`cards` | [Array&lt;Card&gt;](Card.md)
`entry` | [DictEntrySummary](DictEntrySummary.md)
`entrySeq` | number
`failures14d` | number
`note` | [NoteSummary](NoteSummary.md)
`noteId` | string
`score` | number

## Example

```typescript
import type { HotWord } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "cards": null,
  "entry": null,
  "entrySeq": null,
  "failures14d": null,
  "note": null,
  "noteId": null,
  "score": null,
} satisfies HotWord

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as HotWord
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


