
# DictRelease

The current dictionary release (`ref.dict_release`). The artifact formats (zstd SQLite package, `kotobaya-dict-diff` diff), what `blob_hash` covers and how a device installs or updates are defined in `contracts/db/DICT_PACKAGE.md`.

## Properties

Name | Type
------------ | -------------
`artifacts` | [Array&lt;DictReleaseArtifactsInner&gt;](DictReleaseArtifactsInner.md)
`builtAt` | Date
`freqLists` | [Array&lt;DictReleaseFreqListsInner&gt;](DictReleaseFreqListsInner.md)
`id` | number
`loadedAt` | Date
`sources` | { [key: string]: any; }
`version` | string

## Example

```typescript
import type { DictRelease } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "artifacts": null,
  "builtAt": null,
  "freqLists": null,
  "id": null,
  "loadedAt": null,
  "sources": null,
  "version": null,
} satisfies DictRelease

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictRelease
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


