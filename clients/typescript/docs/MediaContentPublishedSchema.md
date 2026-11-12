
# MediaContentPublishedSchema

Server publishes parse progress and, when parsed, a new content snapshot. OPS.md#mediacontent_published

## Properties

Name | Type
------------ | -------------
`contentBlobHash` | string
`contentVersion` | number
`mediaId` | string
`parseError` | string
`parseStatus` | string

## Example

```typescript
import type { MediaContentPublishedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "contentBlobHash": null,
  "contentVersion": null,
  "mediaId": null,
  "parseError": null,
  "parseStatus": null,
} satisfies MediaContentPublishedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaContentPublishedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


