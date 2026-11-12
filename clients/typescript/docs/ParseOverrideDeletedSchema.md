
# ParseOverrideDeletedSchema

Tombstones a parse override (permanent). Server only: devices skip it. OPS.md#parse_overridedeleted

## Properties

Name | Type
------------ | -------------
`id` | string

## Example

```typescript
import type { ParseOverrideDeletedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
} satisfies ParseOverrideDeletedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParseOverrideDeletedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


