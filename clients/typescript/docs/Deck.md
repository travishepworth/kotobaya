
# Deck

A deck (`app.deck`).

## Properties

Name | Type
------------ | -------------
`contentBlobHash` | string
`contentVersion` | number
`createdAt` | Date
`deletedAt` | Date
`enabled` | boolean
`id` | string
`itemCount` | number
`language` | string
`listKey` | string
`mediaId` | string
`name` | string
`newCardsPerDay` | number
`priority` | number
`query` | string
`reviewsPerDay` | number
`settings` | [DeckSettings](DeckSettings.md)
`type` | [DeckType](DeckType.md)
`updatedAt` | Date

## Example

```typescript
import type { Deck } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "contentBlobHash": null,
  "contentVersion": null,
  "createdAt": null,
  "deletedAt": null,
  "enabled": null,
  "id": null,
  "itemCount": null,
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
  "updatedAt": null,
} satisfies Deck

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as Deck
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


