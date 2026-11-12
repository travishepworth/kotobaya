
# SettingsSetSchema

Writes one settings key (one register per key). OPS.md#settingsset

## Properties

Name | Type
------------ | -------------
`key` | string
`value` | any

## Example

```typescript
import type { SettingsSetSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "key": null,
  "value": null,
} satisfies SettingsSetSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SettingsSetSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


