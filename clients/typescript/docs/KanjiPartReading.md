
# KanjiPartReading

ADR-041. Which reading of a kanji a word uses (`KanjiPart.used_reading`).

## Properties

Name | Type
------------ | -------------
`kana` | string
`reading` | string
`type` | string

## Example

```typescript
import type { KanjiPartReading } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "kana": null,
  "reading": null,
  "type": null,
} satisfies KanjiPartReading

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as KanjiPartReading
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


