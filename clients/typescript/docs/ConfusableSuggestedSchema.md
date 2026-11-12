
# ConfusableSuggestedSchema

Server suggestion (status \'suggested\'). Never overrides a user decision. OPS.md#confusablesuggested

## Properties

Name | Type
------------ | -------------
`noteAId` | string
`noteBId` | string
`reason` | string

## Example

```typescript
import type { ConfusableSuggestedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "noteAId": null,
  "noteBId": null,
  "reason": null,
} satisfies ConfusableSuggestedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ConfusableSuggestedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


