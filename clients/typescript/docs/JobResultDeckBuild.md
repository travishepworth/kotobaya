
# JobResultDeckBuild

Result of a deck build (`contracts/db/VOCAB_LISTS.md` 4.4). `list_key`, `changed` and `unmatched` were added by C-21; every C-21 producer sets them.

## Properties

Name | Type
------------ | -------------
`changed` | boolean
`contentVersion` | number
`deckId` | string
`items` | number
`listKey` | string
`unmatched` | number

## Example

```typescript
import type { JobResultDeckBuild } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "changed": null,
  "contentVersion": null,
  "deckId": null,
  "items": null,
  "listKey": null,
  "unmatched": null,
} satisfies JobResultDeckBuild

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobResultDeckBuild
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


