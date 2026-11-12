
# MediaDeletedSchema

Tombstones a media (permanent); bulk content is hard-deleted and the server tombstones its deck. OPS.md#mediadeleted

## Properties

Name | Type
------------ | -------------
`id` | string

## Example

```typescript
import type { MediaDeletedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
} satisfies MediaDeletedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaDeletedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


