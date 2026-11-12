
# MediaDeckOptions

Initial options of the media deck (only used when the import creates it).

## Properties

Name | Type
------------ | -------------
`enabled` | boolean
`newCardsPerDay` | number
`priority` | number

## Example

```typescript
import type { MediaDeckOptions } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "enabled": null,
  "newCardsPerDay": null,
  "priority": null,
} satisfies MediaDeckOptions

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as MediaDeckOptions
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


