
# CardFlagSetSchema

Writes register \'flags\'. OPS.md#cardflag_set

## Properties

Name | Type
------------ | -------------
`flags` | number
`id` | string

## Example

```typescript
import type { CardFlagSetSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "flags": null,
  "id": null,
} satisfies CardFlagSetSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardFlagSetSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


