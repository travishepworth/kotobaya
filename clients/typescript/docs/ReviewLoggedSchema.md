
# ReviewLoggedSchema

Appends one row to review_log. Never conflicts. OPS.md#reviewlogged

## Properties

Name | Type
------------ | -------------
`button` | string
`cardId` | string
`durationMs` | number
`id` | string
`importSource` | string
`manualAction` | string
`manualDueAt` | number
`rating` | number
`replayHlc` | string
`reviewType` | string
`reviewedAt` | number
`sentenceId` | string
`utcOffsetMin` | number

## Example

```typescript
import type { ReviewLoggedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "button": null,
  "cardId": null,
  "durationMs": null,
  "id": null,
  "importSource": null,
  "manualAction": null,
  "manualDueAt": null,
  "rating": null,
  "replayHlc": null,
  "reviewType": null,
  "reviewedAt": null,
  "sentenceId": null,
  "utcOffsetMin": null,
} satisfies ReviewLoggedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewLoggedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


