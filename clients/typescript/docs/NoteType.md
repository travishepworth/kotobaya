
# NoteType

A note type (`app.note_type`).

## Properties

Name | Type
------------ | -------------
`baseType` | [NoteBaseType](NoteBaseType.md)
`builtin` | boolean
`createdAt` | Date
`css` | string
`deletedAt` | Date
`fields` | [Array&lt;NoteField&gt;](NoteField.md)
`id` | string
`name` | string
`templates` | [{ [key: string]: CardTemplate; }](CardTemplate.md)
`updatedAt` | Date

## Example

```typescript
import type { NoteType } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "baseType": null,
  "builtin": null,
  "createdAt": null,
  "css": null,
  "deletedAt": null,
  "fields": null,
  "id": null,
  "name": null,
  "templates": null,
  "updatedAt": null,
} satisfies NoteType

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteType
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


