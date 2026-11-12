
# HotKanji

A kanji you are currently struggling with.

## Properties

Name | Type
------------ | -------------
`literal` | string
`meanings` | Array&lt;string&gt;
`noteId` | string
`score` | number
`wordCount` | number

## Example

```typescript
import type { HotKanji } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "literal": null,
  "meanings": null,
  "noteId": null,
  "score": null,
  "wordCount": null,
} satisfies HotKanji

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as HotKanji
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


