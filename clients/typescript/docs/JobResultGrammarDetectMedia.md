
# JobResultGrammarDetectMedia

Result of a per-media `grammar-detect` job. Three shapes: - published: `changed: true`, `content_version` = the version this job published,   `spans`, `invalid_rules`; - nothing to do: `changed: false`, `content_version` = the media\'s current version   (detected spans already equal the computed ones), `spans`, `invalid_rules`; - skipped: `changed: false`, `skipped` set, no `content_version`, `spans` or   `invalid_rules`. A retry whose earlier attempt already published (MEDIA_PIPELINE.md 3.2 step 1) reports `changed: true` and `content_version` only. 

## Properties

Name | Type
------------ | -------------
`changed` | boolean
`contentVersion` | number
`invalidRules` | number
`mediaId` | string
`skipped` | string
`spans` | number

## Example

```typescript
import type { JobResultGrammarDetectMedia } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "changed": null,
  "contentVersion": null,
  "invalidRules": null,
  "mediaId": null,
  "skipped": null,
  "spans": null,
} satisfies JobResultGrammarDetectMedia

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultGrammarDetectMedia
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


