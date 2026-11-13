
# DictHeadword

The label fields of one dictionary entry (`listDictHeadwords`, C-18). A subset of `DictEntrySummary` with identical values; no caller overlay.

## Properties

Name | Type
------------ | -------------
`glosses` | Array&lt;string&gt;
`headword` | string
`headwordReading` | string
`seq` | number

## Example

```typescript
import type { DictHeadword } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "glosses": null,
  "headword": null,
  "headwordReading": null,
  "seq": null,
} satisfies DictHeadword

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictHeadword
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


