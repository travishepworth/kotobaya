
# NoteTypeCreate

New note type.

## Properties

Name | Type
------------ | -------------
`baseType` | [NoteBaseType](NoteBaseType.md)
`css` | string
`fields` | [Array&lt;NoteField&gt;](NoteField.md)
`id` | string
`name` | string
`templates` | [{ [key: string]: CardTemplate; }](CardTemplate.md)

## Example

```typescript
import type { NoteTypeCreate } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "baseType": null,
  "css": null,
  "fields": null,
  "id": null,
  "name": null,
  "templates": null,
} satisfies NoteTypeCreate

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteTypeCreate
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


