
# NoteSummary

The parts of a note needed to render and identify a card.

## Properties

Name | Type
------------ | -------------
`entrySeq` | number
`fields` | { [key: string]: string; }
`grammarPointId` | string
`id` | string
`kanjiLiteral` | string
`mnemonic` | string
`noteTypeId` | string
`sourceSentence` | string
`tags` | Array&lt;string&gt;
`type` | [NoteBaseType](NoteBaseType.md)

## Example

```typescript
import type { NoteSummary } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "entrySeq": null,
  "fields": null,
  "grammarPointId": null,
  "id": null,
  "kanjiLiteral": null,
  "mnemonic": null,
  "noteTypeId": null,
  "sourceSentence": null,
  "tags": null,
  "type": null,
} satisfies NoteSummary

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteSummary
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


