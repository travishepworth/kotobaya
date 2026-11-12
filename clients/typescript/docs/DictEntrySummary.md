
# DictEntrySummary

Short form of a dictionary entry with the caller\'s overlay.

## Properties

Name | Type
------------ | -------------
`freqRank` | number
`glosses` | Array&lt;string&gt;
`headword` | string
`headwordReading` | string
`isCommon` | boolean
`noteId` | string
`pos` | Array&lt;string&gt;
`retired` | boolean
`seq` | number
`status` | [WordStatusValue](WordStatusValue.md)

## Example

```typescript
import type { DictEntrySummary } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "freqRank": null,
  "glosses": null,
  "headword": null,
  "headwordReading": null,
  "isCommon": null,
  "noteId": null,
  "pos": null,
  "retired": null,
  "seq": null,
  "status": null,
} satisfies DictEntrySummary

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictEntrySummary
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


