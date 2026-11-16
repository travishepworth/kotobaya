
# ShellEventNoteCreated

Payload of `note.created` in shell mode - a note was created on this device outside the SPA (AnkiProvider / Takoboto, on-device AnkiConnect) or arrived by pull.

## Properties

Name | Type
------------ | -------------
`noteId` | string
`originDeviceId` | string
`origin` | string
`seq` | number

## Example

```typescript
import type { ShellEventNoteCreated } from '@kotobaya/shell-client'

// TODO: Update the object below with actual values
const example = {
  "noteId": null,
  "originDeviceId": null,
  "origin": null,
  "seq": null,
} satisfies ShellEventNoteCreated

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ShellEventNoteCreated
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


