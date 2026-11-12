
# MediaImportFile

One uploaded source file.

## Properties

Name | Type
------------ | -------------
`audioBlobHash` | string
`blobHash` | string
`filename` | string
`title` | string

## Example

```typescript
import type { MediaImportFile } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "audioBlobHash": null,
  "blobHash": null,
  "filename": null,
  "title": null,
} satisfies MediaImportFile

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaImportFile
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


