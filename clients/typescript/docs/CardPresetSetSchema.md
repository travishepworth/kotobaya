
# CardPresetSetSchema

Writes register \'preset_id\'. A winning write forces a replay of the card. OPS.md#cardpreset_set

## Properties

Name | Type
------------ | -------------
`id` | string
`presetId` | string

## Example

```typescript
import type { CardPresetSetSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "presetId": null,
} satisfies CardPresetSetSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as CardPresetSetSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


