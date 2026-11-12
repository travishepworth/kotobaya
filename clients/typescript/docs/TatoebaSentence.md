
# TatoebaSentence

A Tatoeba sentence pair (`ref.example_sentence`, CC BY 2.0 FR).

## Properties

Name | Type
------------ | -------------
`id` | number
`text` | string
`translation` | string

## Example

```typescript
import type { TatoebaSentence } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "id": null,
  "text": null,
  "translation": null,
} satisfies TatoebaSentence

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as TatoebaSentence
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


