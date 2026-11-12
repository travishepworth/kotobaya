
# CardBulkAction

The action of a bulk call. Which params are required depends on `type` (table on `POST /cards/bulk`).

## Properties

Name | Type
------------ | -------------
`flags` | number
`fromDeckId` | string
`presetId` | string
`tags` | Array&lt;string&gt;
`toDeckId` | string
`type` | string
`until` | Date

## Example

```typescript
import type { CardBulkAction } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "flags": null,
  "fromDeckId": null,
  "presetId": null,
  "tags": null,
  "toDeckId": null,
  "type": null,
  "until": null,
} satisfies CardBulkAction

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardBulkAction
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


