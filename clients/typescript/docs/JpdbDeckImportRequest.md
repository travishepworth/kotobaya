
# JpdbDeckImportRequest

Which jpdb deck to import. Also the payload of `jpdb-deck-import` jobs, with `media_id` added.

## Properties

Name | Type
------------ | -------------
`deck` | [MediaDeckOptions](MediaDeckOptions.md)
`jpdbId` | string
`kind` | [MediaKind](MediaKind.md)
`mediaId` | string
`title` | string

## Example

```typescript
import type { JpdbDeckImportRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deck": null,
  "jpdbId": null,
  "kind": null,
  "mediaId": null,
  "title": null,
} satisfies JpdbDeckImportRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JpdbDeckImportRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


