
# ReviewQueue

A prefix of today\'s review queue.

## Properties

Name | Type
------------ | -------------
`counts` | [ReviewQueueCounts](ReviewQueueCounts.md)
`dayEndsAt` | Date
`generatedAt` | Date
`items` | [Array&lt;QueueItem&gt;](QueueItem.md)
`studyDay` | Date

## Example

```typescript
import type { ReviewQueue } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "counts": null,
  "dayEndsAt": null,
  "generatedAt": null,
  "items": null,
  "studyDay": null,
} satisfies ReviewQueue

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewQueue
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


