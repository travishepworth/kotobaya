
# DrillSessionRequest

What to drill.

## Properties

Name | Type
------------ | -------------
`cardIds` | Set&lt;string&gt;
`entrySeq` | number
`limit` | number
`mode` | string
`query` | string
`source` | string

## Example

```typescript
import type { DrillSessionRequest } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "cardIds": null,
  "entrySeq": null,
  "limit": null,
  "mode": null,
  "query": null,
  "source": null,
} satisfies DrillSessionRequest

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as DrillSessionRequest
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


