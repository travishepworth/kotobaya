
# ConfusableRejectedSchema

User rejects the pair (status \'rejected\'); the row stays so it is not suggested again. OPS.md#confusablerejected

## Properties

Name | Type
------------ | -------------
`noteAId` | string
`noteBId` | string
`reason` | string

## Example

```typescript
import type { ConfusableRejectedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "noteAId": null,
  "noteBId": null,
  "reason": null,
} satisfies ConfusableRejectedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ConfusableRejectedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


