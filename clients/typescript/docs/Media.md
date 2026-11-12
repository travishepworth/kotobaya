
# Media

A media (`app.media`).

## Properties

Name | Type
------------ | -------------
`contentBlobHash` | string
`contentVersion` | number
`coverBlobHash` | string
`createdAt` | Date
`deletedAt` | Date
`id` | string
`jpdbId` | string
`kind` | [MediaKind](MediaKind.md)
`language` | string
`parseError` | string
`parseStatus` | string
`segments` | [Array&lt;MediaSegment&gt;](MediaSegment.md)
`source` | string
`title` | string
`updatedAt` | Date

## Example

```typescript
import type { Media } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "contentBlobHash": null,
  "contentVersion": null,
  "coverBlobHash": null,
  "createdAt": null,
  "deletedAt": null,
  "id": null,
  "jpdbId": null,
  "kind": null,
  "language": null,
  "parseError": null,
  "parseStatus": null,
  "segments": null,
  "source": null,
  "title": null,
  "updatedAt": null,
} satisfies Media

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Media
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


