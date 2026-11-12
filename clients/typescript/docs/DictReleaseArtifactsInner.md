
# DictReleaseArtifactsInner


## Properties

Name | Type
------------ | -------------
`blobHash` | string
`fromRelease` | number
`kind` | string
`scope` | string
`sizeBytes` | number

## Example

```typescript
import type { DictReleaseArtifactsInner } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "blobHash": null,
  "fromRelease": null,
  "kind": null,
  "scope": null,
  "sizeBytes": null,
} satisfies DictReleaseArtifactsInner

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictReleaseArtifactsInner
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


