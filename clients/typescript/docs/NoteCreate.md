
# NoteCreate

New note.

## Properties

Name | Type
------------ | -------------
`cardKinds` | Set&lt;string&gt;
`deckId` | string
`entrySeq` | number
`fields` | { [key: string]: string; }
`grammarPointId` | string
`id` | string
`kanjiLiteral` | string
`mnemonic` | string
`noteTypeId` | string
`origin` | string
`sourceMediaId` | string
`sourceSentence` | string
`sourceSentenceId` | string
`tags` | Array&lt;string&gt;

## Example

```typescript
import type { NoteCreate } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "cardKinds": null,
  "deckId": null,
  "entrySeq": null,
  "fields": null,
  "grammarPointId": null,
  "id": null,
  "kanjiLiteral": null,
  "mnemonic": null,
  "noteTypeId": null,
  "origin": null,
  "sourceMediaId": null,
  "sourceSentence": null,
  "sourceSentenceId": null,
  "tags": null,
} satisfies NoteCreate

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteCreate
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


