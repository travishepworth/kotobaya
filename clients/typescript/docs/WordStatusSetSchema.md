
# WordStatusSetSchema

Writes the explicit status override of a dictionary entry (one register: override_status + override_origin at override_hlc). OPS.md#wordstatus_set

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`origin` | string
`status` | string

## Example

```typescript
import type { WordStatusSetSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "origin": null,
  "status": null,
} satisfies WordStatusSetSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as WordStatusSetSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


