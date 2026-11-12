
# ParseOverridePut

The whole override (replaces any existing row with the id).

## Properties

Name | Type
------------ | -------------
`matchText` | string
`mediaId` | string
`note` | string
`sentenceId` | string
`tokens` | [Array&lt;OverrideToken&gt;](OverrideToken.md)

## Example

```typescript
import type { ParseOverridePut } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "matchText": null,
  "mediaId": null,
  "note": null,
  "sentenceId": null,
  "tokens": null,
} satisfies ParseOverridePut

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ParseOverridePut
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


