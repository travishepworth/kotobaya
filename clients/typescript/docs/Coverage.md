
# Coverage

Coverage numbers. Entries are classified by effective word status after removing the classes named in `hide`. `unique` counts distinct entries; `tokens` weights each entry by its occurrence count and is the number that predicts comprehension. 

## Properties

Name | Type
------------ | -------------
`computedAt` | Date
`curve` | [Array&lt;CoverageCurveInner&gt;](CoverageCurveInner.md)
`deckId` | string
`difficulty` | [CoverageDifficulty](CoverageDifficulty.md)
`hide` | Array&lt;string&gt;
`ignoredCount` | number
`segmentId` | string
`segments` | [Array&lt;CoverageSegmentsInner&gt;](CoverageSegmentsInner.md)
`tokens` | [CoverageTokens](CoverageTokens.md)
`unique` | [CoverageCounts](CoverageCounts.md)

## Example

```typescript
import type { Coverage } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "computedAt": null,
  "curve": null,
  "deckId": null,
  "difficulty": null,
  "hide": null,
  "ignoredCount": null,
  "segmentId": null,
  "segments": null,
  "tokens": null,
  "unique": null,
} satisfies Coverage

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Coverage
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


