# ReviewLoop

ADR-037. The review loop's state for one `getReviewQueue` request: the wire form of the core's `LoopState` (`ReviewLoop.evaluate`), which is the only place a phase or a count is decided. Scope: computed for the request's `deck_id` (as `items`); **unaffected by `kinds` and `limit`**, and - except `phase`, `keep_going`, `keep_going_available`, `until_stop` and `until_stop_parts` - **by `keep_going`**. When the request materialized deck entries (C-27) it describes the state after the last rebuild. All members are required. Invariants: `ready.total = ready.learning + ready.review`; `backlog.reviews >= ready.review`; `backlog.beyond_limit = backlog.reviews - ready.review`, 0 when `reviews.limit` is null; `later_today.next_due_at` is null iff `later_today.count` is 0, else after `generated_at` and before `day_ends_at`; `phase = reviewing` whenever `items` is non-empty; `goal_reached` / `done_for_today` only with `keep_going = false`. ADR-049: `phase = waiting` iff nothing is served and `later_today.count > 0`; a stop screen (`goal_reached`, `done_for_today`, `empty`) implies `ready.total = 0`, `later_today.count = 0` and `until_stop = 0`, and `until_stop = 0` implies a stop screen; `keep_going_available` is true in `goal_reached` / `done_for_today`, false in `empty`, false with `keep_going = true`; `until_stop_parts` sums to `until_stop`. `until_stop` (ADR-038) and `until_stop_parts` (ADR-049) are the numbers besides `phase`, `keep_going` and `keep_going_available` that depend on `keep_going`, and with `keep_going=true` on `limit`. `until_stop`, `keep_going_available` and `until_stop_parts` are not in `required` only because older servers do not send them. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**backlog** | [**ReviewLoopBacklog**](ReviewLoopBacklog.md) |  | 
**keep_going** | **bool** | Echo of the request&#39;s &#x60;keep_going&#x60; (false when omitted). | 
**keep_going_available** | **bool** | ADR-049 (additive). **Show a Keep going button now** (core &#x60;LoopState.keepGoingAvailable&#x60;). True iff the request had &#x60;keep_going&#x3D;false&#x60; (or omitted it), a normal request serves nothing (no item, nothing to materialize) and the same request with &#x60;keep_going&#x3D;true&#x60; would serve at least one card. Always true in &#x60;goal_reached&#x60; / &#x60;done_for_today&#x60;; in &#x60;waiting&#x60; true when a backlog beyond the review cap or a new card exists, else false; false in &#x60;reviewing&#x60; for &#x60;keep_going&#x3D;false&#x60;; false in &#x60;empty&#x60;; always false for &#x60;keep_going&#x3D;true&#x60;. Unaffected by &#x60;kinds&#x60; and &#x60;limit&#x60;. Clients render the button from this member only. Sent by every server and local API implementing ADR-049; absent (older) → clients use &#x60;phase&#x60; in (&#x60;goal_reached&#x60;, &#x60;done_for_today&#x60;). Examples: goal reached, 14 learning cards on their timers, new cards remain → &#x60;waiting&#x60;, true. Only learning cards on their timers, nothing new, no backlog → &#x60;waiting&#x60;, false.  | [optional] 
**later_today** | [**ReviewLoopLaterToday**](ReviewLoopLaterToday.md) |  | 
**new** | [**ReviewLoopNew**](ReviewLoopNew.md) |  | 
**phase** | **str** | Where the loop stands (rule under &#x60;getReviewQueue&#x60; \&quot;Review loop\&quot;; core &#x60;LoopPhase&#x60;). &#x60;reviewing&#x60;: a card is served (or a deck entry awaits materialization). &#x60;waiting&#x60; (ADR-049: checked before the stop screens): nothing is served and a learning card falls due at &#x60;later_today.next_due_at&#x60;; Keep going may be available (&#x60;keep_going_available&#x60;). &#x60;goal_reached&#x60; (reviews unlimited) / &#x60;done_for_today&#x60; (review cap on): nothing ready under the limits, nothing due later today, Keep going would serve a card. &#x60;empty&#x60;: nothing left today, no Keep going. The set is open for readers: an unknown value is treated as &#x60;reviewing&#x60; when &#x60;items&#x60; is non-empty, else as &#x60;empty&#x60;.  | 
**ready** | [**ReviewLoopReady**](ReviewLoopReady.md) |  | 
**reviews** | [**ReviewLoopReviews**](ReviewLoopReviews.md) |  | 
**until_stop** | **int** | ADR-038 (additive). Answers left until the stop screen (core &#x60;LoopState.untilStop&#x60;); the review progress bar&#39;s total is &#x60;done + until_stop&#x60; (client rule C3). Each card counts once, so the total grows when a card comes back (Again) or a new card enters its learning steps. - &#x60;keep_going &#x3D; false&#x60;: &#x60;ready.learning + later_today.count + ready.review&#x60; + the new   cards that can still be introduced today (the smaller of the goal&#39;s remainder and   what the decks can supply under their limits; kanji cards served ahead of their   word and deck entries not yet materialized included; **not** bounded by &#x60;limit&#x60;).   These are the answers before the stop screen (&#x60;goal_reached&#x60; / &#x60;done_for_today&#x60;   / &#x60;empty&#x60;). ADR-049: in &#x60;waiting&#x60; it equals &#x60;later_today.count&#x60; (&gt;&#x3D; 1); at a stop   screen it is always 0, and 0 only there. - &#x60;keep_going &#x3D; true&#x60;: the stream is endless, so it is what is in sight:   &#x60;ready.learning + later_today.count + backlog.reviews&#x60; + the new cards this   response drew (about &#x60;limit&#x60;; a kanji group may overshoot). Unaffected by &#x60;kinds&#x60;. &#x60;phase &#x3D; reviewing&#x60; implies &#x60;until_stop &gt;&#x3D; 1&#x60;. Sent by every server and local API implementing ADR-038; absent (older) → clients use &#x60;ready.total&#x60;. Examples: 0 ready, 26 later, goal 40 with 0 introduced, 100 new cards → 66. Goal 40 with 10 introduced, 30 reviews due, 5 learning cards on a timer → 65. Both limits reached with 135 reviews beyond the cap → 0; the same with &#x60;keep_going&#x3D;true&amp;limit&#x3D;10&#x60; → 145.  | [optional] 
**until_stop_parts** | [**ReviewLoopUntilStopParts**](ReviewLoopUntilStopParts.md) |  | [optional] 

## Example

```python
from kotobaya_client.models.review_loop import ReviewLoop

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoop from a JSON string
review_loop_instance = ReviewLoop.from_json(json)
# print the JSON string representation of the object
print(ReviewLoop.to_json())

# convert the object into a dict
review_loop_dict = review_loop_instance.to_dict()
# create an instance of ReviewLoop from a dict
review_loop_from_dict = ReviewLoop.from_dict(review_loop_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


