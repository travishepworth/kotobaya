
# Card

A card (`app.card`). The schedule properties are a cache computed from the review log.

## Properties

Name | Type
------------ | -------------
`buriedUntil` | Date
`createdAt` | Date
`deletedAt` | Date
`difficulty` | number
`dueAt` | Date
`flags` | number
`id` | string
`intervalDays` | number
`kind` | string
`lapses` | number
`lastReviewAt` | Date
`leech` | boolean
`mergedIntoId` | string
`note` | [NoteSummary](NoteSummary.md)
`noteId` | string
`presetId` | string
`reps` | number
`stability` | number
`state` | [CardState](CardState.md)
`step` | number
`suspended` | boolean
`updatedAt` | Date

## Example

```typescript
import type { Card } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "buriedUntil": null,
  "createdAt": null,
  "deletedAt": null,
  "difficulty": null,
  "dueAt": null,
  "flags": null,
  "id": null,
  "intervalDays": null,
  "kind": null,
  "lapses": null,
  "lastReviewAt": null,
  "leech": null,
  "mergedIntoId": null,
  "note": null,
  "noteId": null,
  "presetId": null,
  "reps": null,
  "stability": null,
  "state": null,
  "step": null,
  "suspended": null,
  "updatedAt": null,
} satisfies Card

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Card
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


