
# Blob

Metadata of a blob (`app.blob`).

## Properties

Name | Type
------------ | -------------
`contentType` | string
`createdAt` | Date
`hash` | string
`kind` | string
`origin` | string
`sizeBytes` | number

## Example

```typescript
import type { Blob } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "contentType": null,
  "createdAt": null,
  "hash": null,
  "kind": null,
  "origin": null,
  "sizeBytes": null,
} satisfies Blob

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Blob
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


