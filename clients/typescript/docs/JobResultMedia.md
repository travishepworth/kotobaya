
# JobResultMedia

Result of a media import, jpdb deck import or parse.

## Properties

Name | Type
------------ | -------------
`contentVersion` | number
`mediaId` | string
`segments` | number
`sentences` | number
`tokens` | number
`uniqueEntries` | number
`unmapped` | [Array&lt;JobResultMediaUnmappedInner&gt;](JobResultMediaUnmappedInner.md)
`unmappedTokens` | number

## Example

```typescript
import type { JobResultMedia } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "contentVersion": null,
  "mediaId": null,
  "segments": null,
  "sentences": null,
  "tokens": null,
  "uniqueEntries": null,
  "unmapped": null,
  "unmappedTokens": null,
} satisfies JobResultMedia

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultMedia
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


