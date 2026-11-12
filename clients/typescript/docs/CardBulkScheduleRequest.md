
# CardBulkScheduleRequest

Selector plus a schedule action. Exactly one of `card_ids` and `query` (as `CardBulkRequest`).

## Properties

Name | Type
------------ | -------------
`action` | [CardBulkScheduleRequestAction](CardBulkScheduleRequestAction.md)
`cardIds` | Set&lt;string&gt;
`dryRun` | boolean
`query` | string

## Example

```typescript
import type { CardBulkScheduleRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "action": null,
  "cardIds": null,
  "dryRun": null,
  "query": null,
} satisfies CardBulkScheduleRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardBulkScheduleRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


