
# DeckPatch

Changes to a deck. At least one property.

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`name` | string
`newCardsPerDay` | number
`priority` | number
`query` | string
`reviewsPerDay` | number
`settings` | { [key: string]: any; }

## Example

```typescript
import type { DeckPatch } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "name": null,
  "newCardsPerDay": null,
  "priority": null,
  "query": null,
  "reviewsPerDay": null,
  "settings": null,
} satisfies DeckPatch

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckPatch
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


