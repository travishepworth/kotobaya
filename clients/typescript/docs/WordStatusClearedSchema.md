
# WordStatusClearedSchema

Clears the override (same register as word.status_set, value null), so the effective status falls back to the automatic one. OPS.md#wordstatus_cleared

## Properties

Name | Type
------------ | -------------
`entrySeq` | number

## Example

```typescript
import type { WordStatusClearedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
} satisfies WordStatusClearedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as WordStatusClearedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


