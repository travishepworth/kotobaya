
# ReviewQueueCounts

Cards remaining today under the current limits (whole queue, not only `items`; unaffected by `kinds`).

## Properties

Name | Type
------------ | -------------
`learning` | number
`_new` | number
`review` | number

## Example

```typescript
import type { ReviewQueueCounts } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "learning": null,
  "_new": null,
  "review": null,
} satisfies ReviewQueueCounts

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewQueueCounts
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


