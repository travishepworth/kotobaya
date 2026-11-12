
# ReviewLog

A row of `app.review_log`.

## Properties

Name | Type
------------ | -------------
`button` | [ReviewButton](ReviewButton.md)
`cardId` | string
`deviceId` | string
`durationMs` | number
`hlc` | string
`id` | string
`importSource` | string
`manualAction` | string
`manualDueAt` | Date
`rating` | number
`reviewType` | [ReviewType](ReviewType.md)
`reviewedAt` | Date
`sentenceId` | string
`undoneAt` | Date
`utcOffsetMin` | number

## Example

```typescript
import type { ReviewLog } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "button": null,
  "cardId": null,
  "deviceId": null,
  "durationMs": null,
  "hlc": null,
  "id": null,
  "importSource": null,
  "manualAction": null,
  "manualDueAt": null,
  "rating": null,
  "reviewType": null,
  "reviewedAt": null,
  "sentenceId": null,
  "undoneAt": null,
  "utcOffsetMin": null,
} satisfies ReviewLog

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewLog
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


