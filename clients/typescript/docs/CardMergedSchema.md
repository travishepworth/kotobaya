
# CardMergedSchema

Server verdict that two live cards had the same (note, kind), or that a card of a merged note is folded into the survivor\'s card. OPS.md#cardmerged

## Properties

Name | Type
------------ | -------------
`loserId` | string
`survivorId` | string

## Example

```typescript
import type { CardMergedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "loserId": null,
  "survivorId": null,
} satisfies CardMergedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardMergedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


