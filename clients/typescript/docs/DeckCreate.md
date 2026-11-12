
# DeckCreate

New deck. Which properties are allowed depends on `type` (see `POST /decks`).

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`id` | string
`listKey` | string
`name` | string
`newCardsPerDay` | number
`priority` | number
`query` | string
`reviewsPerDay` | number
`settings` | [DeckSettings](DeckSettings.md)
`type` | string

## Example

```typescript
import type { DeckCreate } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "id": null,
  "listKey": null,
  "name": null,
  "newCardsPerDay": null,
  "priority": null,
  "query": null,
  "reviewsPerDay": null,
  "settings": null,
  "type": null,
} satisfies DeckCreate

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckCreate
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


