
# Template

One card kind of a note type. Unknown keys are preserved.

## Properties

Name | Type
------------ | -------------
`back` | string
`_default` | boolean
`front` | string

## Example

```typescript
import type { Template } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "back": null,
  "_default": null,
  "front": null,
} satisfies Template

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Template
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


