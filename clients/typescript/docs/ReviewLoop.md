
# ReviewLoop

ADR-037. The review loop\'s state for one `getReviewQueue` request: the wire form of the core\'s `LoopState` (`ReviewLoop.evaluate`), which is the only place a phase or a count is decided. Scope: computed for the request\'s `deck_id` (as `items`); **unaffected by `kinds` and `limit`**, and - except `phase` and `keep_going` - **by `keep_going`**. When the request materialized deck entries (C-27) it describes the state after the last rebuild. All members are required. Invariants: `ready.total = ready.learning + ready.review`; `backlog.reviews >= ready.review`; `backlog.beyond_limit = backlog.reviews - ready.review`, 0 when `reviews.limit` is null; `later_today.next_due_at` is null iff `later_today.count` is 0, else after `generated_at` and before `day_ends_at`; `phase = reviewing` whenever `items` is non-empty; `goal_reached` / `done_for_today` only with `keep_going = false`. `until_stop` (ADR-038) is the one number besides `phase` and `keep_going` that depends on `keep_going`, and with `keep_going=true` on `limit`; it is not in `required` only because servers older than ADR-038 do not send it. 

## Properties

Name | Type
------------ | -------------
`backlog` | [ReviewLoopBacklog](ReviewLoopBacklog.md)
`keepGoing` | boolean
`laterToday` | [ReviewLoopLaterToday](ReviewLoopLaterToday.md)
`_new` | [ReviewLoopNew](ReviewLoopNew.md)
`phase` | string
`ready` | [ReviewLoopReady](ReviewLoopReady.md)
`reviews` | [ReviewLoopReviews](ReviewLoopReviews.md)
`untilStop` | number

## Example

```typescript
import type { ReviewLoop } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "backlog": null,
  "keepGoing": null,
  "laterToday": null,
  "_new": null,
  "phase": null,
  "ready": null,
  "reviews": null,
  "untilStop": null,
} satisfies ReviewLoop

console.log(example)

// Convert the instance to a JSON string
const exampleJSON: string = JSON.stringify(example)
console.log(exampleJSON)

// Parse the JSON string back to an object
const exampleParsed = JSON.parse(exampleJSON) as ReviewLoop
console.log(exampleParsed)
```

[[Back to top]](#) [[Back to API list]](../README.md#api-endpoints) [[Back to Model list]](../README.md#models) [[Back to README]](../README.md)


