
# Confusable

A confusable pair (`app.confusable`), with merge losers resolved to survivors.

## Properties

Name | Type
------------ | -------------
`createdAt` | Date
`noteA` | [NoteSummary](NoteSummary.md)
`noteAId` | string
`noteB` | [NoteSummary](NoteSummary.md)
`noteBId` | string
`reason` | [ConfusableReason](ConfusableReason.md)
`status` | [ConfusableStatus](ConfusableStatus.md)
`updatedAt` | Date

## Example

```typescript
import type { Confusable } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "createdAt": null,
  "noteA": null,
  "noteAId": null,
  "noteB": null,
  "noteBId": null,
  "reason": null,
  "status": null,
  "updatedAt": null,
} satisfies Confusable

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Confusable
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


