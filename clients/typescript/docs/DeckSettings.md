
# DeckSettings

Open settings object of a deck; each key is its own last-writer-wins register. Unknown keys are preserved.

## Properties

Name | Type
------------ | -------------
`addKanji` | string
`cardKinds` | Array&lt;string&gt;
`order` | string

## Example

```typescript
import type { DeckSettings } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "addKanji": null,
  "cardKinds": null,
  "order": null,
} satisfies DeckSettings

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckSettings
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


