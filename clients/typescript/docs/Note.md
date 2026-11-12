
# Note

A note (`app.note`) with its live cards.

## Properties

Name | Type
------------ | -------------
`cards` | [Array&lt;Card&gt;](Card.md)
`createdAt` | Date
`createdByDeviceId` | string
`deletedAt` | Date
`entrySeq` | number
`fields` | { [key: string]: string; }
`grammarPointId` | string
`id` | string
`kanjiLiteral` | string
`language` | string
`mergedIntoId` | string
`mnemonic` | string
`noteTypeId` | string
`origin` | string
`sourceMediaId` | string
`sourceSentence` | string
`sourceSentenceId` | string
`tags` | Array&lt;string&gt;
`type` | [NoteBaseType](NoteBaseType.md)
`updatedAt` | Date

## Example

```typescript
import type { Note } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "cards": null,
  "createdAt": null,
  "createdByDeviceId": null,
  "deletedAt": null,
  "entrySeq": null,
  "fields": null,
  "grammarPointId": null,
  "id": null,
  "kanjiLiteral": null,
  "language": null,
  "mergedIntoId": null,
  "mnemonic": null,
  "noteTypeId": null,
  "origin": null,
  "sourceMediaId": null,
  "sourceSentence": null,
  "sourceSentenceId": null,
  "tags": null,
  "type": null,
  "updatedAt": null,
} satisfies Note

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Note
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


