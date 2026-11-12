
# DeckUpdatedSchemaSet

Registers to write. At least one.

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`name` | string
`newCardsPerDay` | number
`priority` | number
`query` | string
`reviewsPerDay` | number
`settings` | object

## Example

```typescript
import type { DeckUpdatedSchemaSet } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "name": null,
  "newCardsPerDay": null,
  "priority": null,
  "query": null,
  "reviewsPerDay": null,
  "settings": null,
} satisfies DeckUpdatedSchemaSet

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DeckUpdatedSchemaSet
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


