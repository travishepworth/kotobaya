
# DeckDeletedSchema

Tombstones a deck (permanent). Clients may not delete a media deck directly (delete the media). OPS.md#deckdeleted

## Properties

Name | Type
------------ | -------------
`id` | string

## Example

```typescript
import type { DeckDeletedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
} satisfies DeckDeletedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckDeletedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


