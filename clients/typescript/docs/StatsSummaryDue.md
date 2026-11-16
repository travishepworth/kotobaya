
# StatsSummaryDue

**Deprecated (ADR-037): use `getReviewQueue?limit=1` and its `loop`.** Still sent with the frozen meaning of the deprecated `ReviewQueue.counts` (core `LoopState.legacy`, computed through `ReviewLoop` for an unfiltered normal request with the default `limit` 20): the over-counting `learning` and the batch-bounded `new` documented there. No `loop` is added here. 

## Properties

Name | Type
------------ | -------------
`learning` | number
`_new` | number
`review` | number

## Example

```typescript
import type { StatsSummaryDue } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "learning": null,
  "_new": null,
  "review": null,
} satisfies StatsSummaryDue

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as StatsSummaryDue
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


