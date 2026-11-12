
# CardCreatedSchema

Creates one card of a note. Schedule (cache) columns are never carried; a new card is state \'new\'. OPS.md#cardcreated

## Properties

Name | Type
------------ | -------------
`buriedUntil` | number
`flags` | number
`id` | string
`kind` | string
`noteId` | string
`presetId` | string
`suspended` | boolean

## Example

```typescript
import type { CardCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "buriedUntil": null,
  "flags": null,
  "id": null,
  "kind": null,
  "noteId": null,
  "presetId": null,
  "suspended": null,
} satisfies CardCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


