
# SpeechResolveItem

One thing to resolve - exactly one of `text` (a pair, with optional `voice`) or `note_id`. The \"exactly one\" rule is checked by the server (`422`), not by this schema, so that generated clients get one plain type.

## Properties

Name | Type
------------ | -------------
`noteId` | string
`text` | string
`voice` | string

## Example

```typescript
import type { SpeechResolveItem } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "noteId": null,
  "text": null,
  "voice": null,
} satisfies SpeechResolveItem

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as SpeechResolveItem
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


