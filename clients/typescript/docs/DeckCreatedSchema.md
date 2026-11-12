
# DeckCreatedSchema

Creates a deck. Clients may create type user and smart only; media and list decks are created by the server. OPS.md#deckcreated

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`id` | string
`language` | string
`listKey` | string
`mediaId` | string
`name` | string
`newCardsPerDay` | number
`priority` | number
`query` | string
`reviewsPerDay` | number
`settings` | object
`type` | string

## Example

```typescript
import type { DeckCreatedSchema } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "id": null,
  "language": null,
  "listKey": null,
  "mediaId": null,
  "name": null,
  "newCardsPerDay": null,
  "priority": null,
  "query": null,
  "reviewsPerDay": null,
  "settings": null,
  "type": null,
} satisfies DeckCreatedSchema

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckCreatedSchema
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


