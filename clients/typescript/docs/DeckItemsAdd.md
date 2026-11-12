
# DeckItemsAdd

Items to add to a user deck.

## Properties

Name | Type
------------ | -------------
`items` | [Array&lt;DeckItemsAddItemsInner&gt;](DeckItemsAddItemsInner.md)

## Example

```typescript
import type { DeckItemsAdd } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "items": null,
} satisfies DeckItemsAdd

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckItemsAdd
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


