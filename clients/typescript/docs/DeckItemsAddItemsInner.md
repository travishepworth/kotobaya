
# DeckItemsAddItemsInner

Exactly one of `entry_seq` and `note_id`.

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`noteId` | string
`position` | number

## Example

```typescript
import type { DeckItemsAddItemsInner } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "noteId": null,
  "position": null,
} satisfies DeckItemsAddItemsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckItemsAddItemsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


