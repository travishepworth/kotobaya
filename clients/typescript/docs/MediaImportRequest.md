
# MediaImportRequest

What to import (see `POST /media/import`). Also the payload of `media-import` jobs, with `media_id` always set and `import_id` omitted (it is the job\'s `id`).

## Properties

Name | Type
------------ | -------------
`deck` | [MediaDeckOptions](MediaDeckOptions.md)
`files` | [Array&lt;MediaImportFile&gt;](MediaImportFile.md)
`format` | string
`importId` | string
`kind` | [MediaKind](MediaKind.md)
`mediaId` | string
`options` | [MediaImportRequestOptions](MediaImportRequestOptions.md)
`source` | string
`text` | string
`title` | string
`url` | string

## Example

```typescript
import type { MediaImportRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deck": null,
  "files": null,
  "format": null,
  "importId": null,
  "kind": null,
  "mediaId": null,
  "options": null,
  "source": null,
  "text": null,
  "title": null,
  "url": null,
} satisfies MediaImportRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaImportRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


