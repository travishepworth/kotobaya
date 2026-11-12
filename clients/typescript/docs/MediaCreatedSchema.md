
# MediaCreatedSchema

An import job created a media row. parse_status starts \'pending\', content_version 0. OPS.md#mediacreated

## Properties

Name | Type
------------ | -------------
`coverBlobHash` | string
`id` | string
`jpdbId` | string
`kind` | string
`source` | string
`title` | string

## Example

```typescript
import type { MediaCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "coverBlobHash": null,
  "id": null,
  "jpdbId": null,
  "kind": null,
  "source": null,
  "title": null,
} satisfies MediaCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


