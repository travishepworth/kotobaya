
# NoteUpdatedSchemaSet

Registers to write. At least one. The three source_* properties form one register and must be given together.

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`fields` | { [key: string]: string | null; }
`mnemonic` | string
`sourceMediaId` | string
`sourceSentence` | string
`sourceSentenceId` | string
`tags` | Set&lt;string&gt;

## Example

```typescript
import type { NoteUpdatedSchemaSet } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "fields": null,
  "mnemonic": null,
  "sourceMediaId": null,
  "sourceSentence": null,
  "sourceSentenceId": null,
  "tags": null,
} satisfies NoteUpdatedSchemaSet

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteUpdatedSchemaSet
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


