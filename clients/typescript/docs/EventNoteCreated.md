
# EventNoteCreated

Payload of `note.created`.

## Properties

Name | Type
------------ | -------------
`noteId` | string
`origin` | string
`originDeviceId` | string
`seq` | number

## Example

```typescript
import type { EventNoteCreated } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "noteId": null,
  "origin": null,
  "originDeviceId": null,
  "seq": null,
} satisfies EventNoteCreated

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as EventNoteCreated
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


