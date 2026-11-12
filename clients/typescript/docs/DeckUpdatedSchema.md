
# DeckUpdatedSchema

Sets a subset of a deck\'s registers, all at the op\'s hlc. OPS.md#deckupdated

## Properties

Name | Type
------------ | -------------
`id` | string
`set` | [DeckUpdatedSchemaSet](DeckUpdatedSchemaSet.md)

## Example

```typescript
import type { DeckUpdatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "set": null,
} satisfies DeckUpdatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckUpdatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


