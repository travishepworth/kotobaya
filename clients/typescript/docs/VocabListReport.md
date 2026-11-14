
# VocabListReport

How one vocabulary list\'s source rows were accounted for (C-21; `contracts/db/VOCAB_LISTS.md` 3.3).

## Properties

Name | Type
------------ | -------------
`duplicates` | number
`entries` | number
`id` | string
`shadowed` | number
`sourceRows` | number
`unmatched` | number

## Example

```typescript
import type { VocabListReport } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "duplicates": null,
  "entries": null,
  "id": null,
  "shadowed": null,
  "sourceRows": null,
  "unmatched": null,
} satisfies VocabListReport

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as VocabListReport
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


