
# NoteCreatedSchema

Creates a note. The note\'s `type` is not carried: every replica copies note_type.base_type. Omitted optional properties take the column default and get no clock. OPS.md#notecreated

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`fields` | { [key: string]: string | null; }
`grammarPointId` | string
`id` | string
`kanjiLiteral` | string
`language` | string
`mnemonic` | string
`noteTypeId` | string
`origin` | string
`sourceMediaId` | string
`sourceSentence` | string
`sourceSentenceId` | string
`tags` | Set&lt;string&gt;

## Example

```typescript
import type { NoteCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "fields": null,
  "grammarPointId": null,
  "id": null,
  "kanjiLiteral": null,
  "language": null,
  "mnemonic": null,
  "noteTypeId": null,
  "origin": null,
  "sourceMediaId": null,
  "sourceSentence": null,
  "sourceSentenceId": null,
  "tags": null,
} satisfies NoteCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


