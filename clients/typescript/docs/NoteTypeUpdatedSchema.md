
# NoteTypeUpdatedSchema

Sets a subset of a note type\'s registers, all at the op\'s hlc. OPS.md#note_typeupdated

## Properties

Name | Type
------------ | -------------
`id` | string
`set` | [NoteTypeUpdatedSchemaSet](NoteTypeUpdatedSchemaSet.md)

## Example

```typescript
import type { NoteTypeUpdatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "set": null,
} satisfies NoteTypeUpdatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteTypeUpdatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


