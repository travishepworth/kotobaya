
# NoteField

One field definition of a note type.

## Properties

Name | Type
------------ | -------------
`key` | string
`kind` | string
`label` | string

## Example

```typescript
import type { NoteField } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "key": null,
  "kind": null,
  "label": null,
} satisfies NoteField

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteField
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


