
# CardDeletedSchema

Tombstones a card (permanent). Its reviews stay in review_log. OPS.md#carddeleted

## Properties

Name | Type
------------ | -------------
`id` | string

## Example

```typescript
import type { CardDeletedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
} satisfies CardDeletedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardDeletedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


