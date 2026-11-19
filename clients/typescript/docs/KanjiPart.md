
# KanjiPart

ADR-041. One kanji of a vocab word (`QueueItem.kanji_parts`); the wire form of the core\'s `KanjiPart`. Fields added later will be optional, so clients must ignore unknown members.

## Properties

Name | Type
------------ | -------------
`keyword` | string
`kun` | Array&lt;string&gt;
`literal` | string
`meanings` | Array&lt;string&gt;
`on` | Array&lt;string&gt;
`usedReading` | [KanjiPartReading](KanjiPartReading.md)

## Example

```typescript
import type { KanjiPart } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "keyword": null,
  "kun": null,
  "literal": null,
  "meanings": null,
  "on": null,
  "usedReading": null,
} satisfies KanjiPart

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as KanjiPart
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


