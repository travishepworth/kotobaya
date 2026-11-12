
# JobPayloadDeckBuild

Payload of a `deck-build` job (builds the item snapshot of a list deck).

## Properties

Name | Type
------------ | -------------
`deckId` | string

## Example

```typescript
import type { JobPayloadDeckBuild } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "deckId": null,
} satisfies JobPayloadDeckBuild

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as JobPayloadDeckBuild
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


