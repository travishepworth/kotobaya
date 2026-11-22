
# ReviewLoopUntilStopParts

ADR-049 (additive), amended by ADR-054 (C-34). The numbers behind the review header, Home and the later-today hint (core `LoopState.untilStopParts`; client rule C3): `learning + review + new = until_stop`, always; `later` is information for the later-today hint only and is **not** summed into `until_stop` (nor shown as a header chip or in Home\'s big number). Depends on `keep_going` exactly as `until_stop` does (`review`, `new`). Unaffected by `kinds`. Sent by every server and local API implementing ADR-049; absent (older) → clients show `ready.learning` and `ready.review`, with `later_today.count` in the hint. Example (goal 40 reached, 9 learning cards ready, 14 on their timers, no reviews): `{learning: 9, later: 14, review: 0, new: 0}`, `until_stop` 9 (header \"9 learning\", hint \"14 later today\"). 

## Properties

Name | Type
------------ | -------------
`later` | number
`learning` | number
`_new` | number
`review` | number

## Example

```typescript
import type { ReviewLoopUntilStopParts } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "later": null,
  "learning": null,
  "_new": null,
  "review": null,
} satisfies ReviewLoopUntilStopParts

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewLoopUntilStopParts
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


