
# CardPatch

Changes to a card\'s user registers. At least one property.

## Properties

Name | Type
------------ | -------------
`buriedUntil` | Date
`flags` | number
`presetId` | string
`suspended` | boolean

## Example

```typescript
import type { CardPatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "buriedUntil": null,
  "flags": null,
  "presetId": null,
  "suspended": null,
} satisfies CardPatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardPatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


