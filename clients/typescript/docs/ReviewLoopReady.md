
# ReviewLoopReady

Cards that can be shown now, new cards excluded (\"ready now\").

## Properties

Name | Type
------------ | -------------
`learning` | number
`review` | number
`total` | number

## Example

```typescript
import type { ReviewLoopReady } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "learning": null,
  "review": null,
  "total": null,
} satisfies ReviewLoopReady

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewLoopReady
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


