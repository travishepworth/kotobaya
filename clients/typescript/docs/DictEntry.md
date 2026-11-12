
# DictEntry

A full JMdict entry (`ref.dict_entry` and its satellites) with the caller\'s overlay.

## Properties

Name | Type
------------ | -------------
`freqRank` | number
`freqs` | [Array&lt;DictEntryFreqsInner&gt;](DictEntryFreqsInner.md)
`headword` | string
`headwordReading` | string
`isCommon` | boolean
`kanji` | Array&lt;string&gt;
`kanjiForms` | [Array&lt;DictEntryKanjiFormsInner&gt;](DictEntryKanjiFormsInner.md)
`language` | string
`misc` | Array&lt;string&gt;
`noteId` | string
`pitch` | [Array&lt;DictEntryPitchInner&gt;](DictEntryPitchInner.md)
`pos` | Array&lt;string&gt;
`readings` | [Array&lt;DictEntryReadingsInner&gt;](DictEntryReadingsInner.md)
`retired` | boolean
`senses` | [Array&lt;DictEntrySensesInner&gt;](DictEntrySensesInner.md)
`seq` | number
`status` | [WordStatusValue](WordStatusValue.md)

## Example

```typescript
import type { DictEntry } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "freqRank": null,
  "freqs": null,
  "headword": null,
  "headwordReading": null,
  "isCommon": null,
  "kanji": null,
  "kanjiForms": null,
  "language": null,
  "misc": null,
  "noteId": null,
  "pitch": null,
  "pos": null,
  "readings": null,
  "retired": null,
  "senses": null,
  "seq": null,
  "status": null,
} satisfies DictEntry

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DictEntry
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


