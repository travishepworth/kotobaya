
# ParseOverridePutSchema

Creates or replaces a tokenisation correction (whole row, last-writer-wins on hlc). Server only: devices skip it. OPS.md#parse_overrideput

## Properties

Name | Type
------------ | -------------
`id` | string
`matchText` | string
`mediaId` | string
`note` | string
`sentenceId` | string
`tokens` | [Array&lt;ParseOverridePutSchemaTokensInner&gt;](ParseOverridePutSchemaTokensInner.md)

## Example

```typescript
import type { ParseOverridePutSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "matchText": null,
  "mediaId": null,
  "note": null,
  "sentenceId": null,
  "tokens": null,
} satisfies ParseOverridePutSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParseOverridePutSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


