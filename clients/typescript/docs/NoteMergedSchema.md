
# NoteMergedSchema

Server verdict that two live notes referenced the same entry, kanji or grammar point. Every replica runs the merge procedure. OPS.md#notemerged

## Properties

Name | Type
------------ | -------------
`loserId` | string
`survivorId` | string

## Example

```typescript
import type { NoteMergedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "loserId": null,
  "survivorId": null,
} satisfies NoteMergedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteMergedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


