
# ReviewLoop

ADR-037. The review loop\'s state for one `getReviewQueue` request: the wire form of the core\'s `LoopState` (`ReviewLoop.evaluate`), which is the only place a phase or a count is decided. Scope: computed for the request\'s `deck_id` (as `items`); **unaffected by `kinds` and `limit`**, and - except `phase`, `keep_going`, `keep_going_available`, `until_stop` and `until_stop_parts` - **by `keep_going`**. When the request materialized deck entries (C-27) it describes the state after the last rebuild. All members are required. Invariants: `ready.total = ready.learning + ready.review`; `backlog.reviews >= ready.review`; `backlog.beyond_limit = backlog.reviews - ready.review`, 0 when `reviews.limit` is null; `later_today.next_due_at` is null iff `later_today.count` is 0, else after `generated_at` and before `day_ends_at`; `phase = reviewing` whenever `items` is non-empty; `goal_reached` / `done_for_today` only with `keep_going = false` or with a `keep_going_new_target` (C-31). ADR-049: `phase = waiting` iff nothing is served and `later_today.count > 0`; a stop screen (`goal_reached`, `done_for_today`, `empty`) implies `ready.total = 0`, `later_today.count = 0` and `until_stop = 0`. ADR-054 (amends ADR-049\'s \"`until_stop = 0` implies a stop screen\"): `until_stop >= 1` iff `phase = reviewing`, so `until_stop = 0` iff `phase` is `waiting` or a stop screen; `keep_going_available` is true in `goal_reached` / `done_for_today`, false in `empty`, false in `reviewing`, and false with `keep_going = true` unless a `keep_going_new_target` was sent (C-31); `until_stop = until_stop_parts.learning + until_stop_parts.review + until_stop_parts.new` (ADR-054: `later` is not summed). C-31 (ADR-051), for every response: `ready.total > 0` implies `phase = reviewing` with a non-empty `items` (unless `kinds` excluded them); and with `keep_going = false`, `until_stop_parts.new <= max(0, new.goal - new.today)` (ADR-052: an unfinished keep-going batch is not owed). `until_stop` (ADR-038) and `until_stop_parts` (ADR-049) are the numbers besides `phase`, `keep_going` and `keep_going_available` that depend on `keep_going`, and with `keep_going=true` on `limit`. `until_stop`, `keep_going_available` and `until_stop_parts` are not in `required` only because older servers do not send them. 

## Properties

Name | Type
------------ | -------------
`backlog` | [ReviewLoopBacklog](ReviewLoopBacklog.md)
`keepGoing` | boolean
`keepGoingAvailable` | boolean
`laterToday` | [ReviewLoopLaterToday](ReviewLoopLaterToday.md)
`_new` | [ReviewLoopNew](ReviewLoopNew.md)
`phase` | string
`ready` | [ReviewLoopReady](ReviewLoopReady.md)
`reviews` | [ReviewLoopReviews](ReviewLoopReviews.md)
`untilStop` | number
`untilStopParts` | [ReviewLoopUntilStopParts](ReviewLoopUntilStopParts.md)

## Example

```typescript
import type { ReviewLoop } from '@kotobaya/api-client'

// TODO: Update the object below with actual values
const example = {
  "backlog": null,
  "keepGoing": null,
  "keepGoingAvailable": null,
  "laterToday": null,
  "_new": null,
  "phase": null,
  "ready": null,
  "reviews": null,
  "untilStop": null,
  "untilStopParts": null,
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


