
# CoverageSegmentsInner


## Properties

Name | Type
------------ | -------------
`knownPct` | number
`ordinal` | number
`segmentId` | string
`title` | string
`tokenTotal` | number
`uniqueTotal` | number
`uniqueUnknown` | number

## Example

```typescript
import type { CoverageSegmentsInner } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "knownPct": null,
  "ordinal": null,
  "segmentId": null,
  "title": null,
  "tokenTotal": null,
  "uniqueTotal": null,
  "uniqueUnknown": null,
} satisfies CoverageSegmentsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CoverageSegmentsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


