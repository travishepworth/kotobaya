
# NoteTypeCreatedSchema

Creates a user-defined note type. Built-ins are seeded, never created by ops. OPS.md#note_typecreated

## Properties

Name | Type
------------ | -------------
`baseType` | string
`css` | string
`fields` | [Array&lt;NoteTypeCreatedSchemaFieldsInner&gt;](NoteTypeCreatedSchemaFieldsInner.md)
`id` | string
`name` | string
`templates` | [{ [key: string]: Template; }](Template.md)

## Example

```typescript
import type { NoteTypeCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "baseType": null,
  "css": null,
  "fields": null,
  "id": null,
  "name": null,
  "templates": null,
} satisfies NoteTypeCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteTypeCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


