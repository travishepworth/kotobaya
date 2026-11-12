
# CardUnburiedSchema

Writes register \'buried_until\' = null. OPS.md#cardunburied

## Properties

Name | Type
------------ | -------------
`id` | string

## Example

```typescript
import type { CardUnburiedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
} satisfies CardUnburiedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardUnburiedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


