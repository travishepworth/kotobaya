
# JobResultGrammarDetect

Result of a `grammar-detect` job (C-13). Per-media jobs (payload has `media_id`) report `JobResultGrammarDetectMedia`; fan-out jobs (payload `{}`) report `JobResultGrammarDetectFanOut`.

## Properties

Name | Type
------------ | -------------
`changed` | boolean
`contentVersion` | number
`invalidRules` | number
`mediaId` | string
`skipped` | string
`spans` | number
`enqueued` | number
`media` | number
`rulesDigest` | string

## Example

```typescript
import type { JobResultGrammarDetect } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "changed": null,
  "contentVersion": null,
  "invalidRules": null,
  "mediaId": null,
  "skipped": null,
  "spans": null,
  "enqueued": null,
  "media": null,
  "rulesDigest": null,
} satisfies JobResultGrammarDetect

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultGrammarDetect
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


