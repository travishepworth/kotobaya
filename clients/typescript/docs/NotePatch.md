
# NotePatch

Changes to a note. At least one property. See the register table on `PATCH /notes/{note_id}`.

## Properties

Name | Type
------------ | -------------
`addTags` | Array&lt;string&gt;
`entrySeq` | number
`fields` | { [key: string]: string; }
`mnemonic` | string
`removeTags` | Array&lt;string&gt;
`sourceMediaId` | string
`sourceSentence` | string
`sourceSentenceId` | string
`tags` | Array&lt;string&gt;

## Example

```typescript
import type { NotePatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "addTags": null,
  "entrySeq": null,
  "fields": null,
  "mnemonic": null,
  "removeTags": null,
  "sourceMediaId": null,
  "sourceSentence": null,
  "sourceSentenceId": null,
  "tags": null,
} satisfies NotePatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NotePatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


