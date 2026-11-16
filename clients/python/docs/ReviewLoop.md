# ReviewLoop

ADR-037. The review loop's state for one `getReviewQueue` request: the wire form of the core's `LoopState` (`ReviewLoop.evaluate`), which is the only place a phase or a count is decided. Scope: computed for the request's `deck_id` (as `items`); **unaffected by `kinds` and `limit`**, and - except `phase` and `keep_going` - **by `keep_going`**. When the request materialized deck entries (C-27) it describes the state after the last rebuild. All members are required. Invariants: `ready.total = ready.learning + ready.review`; `backlog.reviews >= ready.review`; `backlog.beyond_limit = backlog.reviews - ready.review`, 0 when `reviews.limit` is null; `later_today.next_due_at` is null iff `later_today.count` is 0, else after `generated_at` and before `day_ends_at`; `phase = reviewing` whenever `items` is non-empty; `goal_reached` / `done_for_today` only with `keep_going = false`. 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**backlog** | [**ReviewLoopBacklog**](ReviewLoopBacklog.md) |  | 
**keep_going** | **bool** | Echo of the request&#39;s &#x60;keep_going&#x60; (false when omitted). | 
**later_today** | [**ReviewLoopLaterToday**](ReviewLoopLaterToday.md) |  | 
**new** | [**ReviewLoopNew**](ReviewLoopNew.md) |  | 
**phase** | **str** | Where the loop stands (rule under &#x60;getReviewQueue&#x60; \&quot;Review loop\&quot;; core &#x60;LoopPhase&#x60;). &#x60;reviewing&#x60;: a card is served (or a deck entry awaits materialization). &#x60;goal_reached&#x60; (reviews unlimited) / &#x60;done_for_today&#x60; (review cap on): nothing ready under the limits, Keep going would serve a card. &#x60;waiting&#x60;: nothing ready, nothing more with keep going, a learning card falls due at &#x60;later_today.next_due_at&#x60;. &#x60;empty&#x60;: nothing left today. The set is open for readers: an unknown value is treated as &#x60;reviewing&#x60; when &#x60;items&#x60; is non-empty, else as &#x60;empty&#x60;.  | 
**ready** | [**ReviewLoopReady**](ReviewLoopReady.md) |  | 
**reviews** | [**ReviewLoopReviews**](ReviewLoopReviews.md) |  | 

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


