
# MediaUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type
------------ | -------------
`coverBlobHash` | string
`kind` | string
`title` | string

## Example

```typescript
import type { MediaUpdatedSchemaSet } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "coverBlobHash": null,
  "kind": null,
  "title": null,
} satisfies MediaUpdatedSchemaSet

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaUpdatedSchemaSet
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


