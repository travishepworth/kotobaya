
# ReviewAnswerRequest

An answer to a card.

## Properties

Name | Type
------------ | -------------
`button` | [ReviewButton](ReviewButton.md)
`cardId` | string
`durationMs` | number
`id` | string
`mode` | string
`rating` | number
`reviewedAt` | Date
`sentenceId` | string
`utcOffsetMin` | number

## Example

```typescript
import type { ReviewAnswerRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "button": null,
  "cardId": null,
  "durationMs": null,
  "id": null,
  "mode": null,
  "rating": null,
  "reviewedAt": null,
  "sentenceId": null,
  "utcOffsetMin": null,
} satisfies ReviewAnswerRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewAnswerRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


