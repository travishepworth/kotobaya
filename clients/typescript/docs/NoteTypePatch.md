
# NoteTypePatch

Changes to a note type. At least one property.

## Properties

Name | Type
------------ | -------------
`css` | string
`fields` | [Array&lt;NoteField&gt;](NoteField.md)
`name` | string
`templates` | [{ [key: string]: CardTemplate; }](CardTemplate.md)

## Example

```typescript
import type { NoteTypePatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "css": null,
  "fields": null,
  "name": null,
  "templates": null,
} satisfies NoteTypePatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as NoteTypePatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


