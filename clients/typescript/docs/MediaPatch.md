
# MediaPatch

Changes to a media. At least one property.

## Properties

Name | Type
------------ | -------------
`coverBlobHash` | string
`kind` | [MediaKind](MediaKind.md)
`title` | string

## Example

```typescript
import type { MediaPatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "coverBlobHash": null,
  "kind": null,
  "title": null,
} satisfies MediaPatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaPatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


