
# ReviewQueueCounts

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **learning** | **kotlin.Int** | Deprecated. Cards in learning or relearning due before the day ends, **ready or not**: &#x60;loop.ready.learning + loop.later_today.count&#x60;. This is the over-count ADR-037 replaces (26 here could mean 0 cards to show now); kept unchanged so old clients see what they always saw.  |  |
| **new** | **kotlin.Int** | Deprecated. New cards still to introduce today: the smaller of the day goal&#39;s remainder and what the decks can supply under their limits, including deck entries not yet materialized (C-27). **Not** bounded by &#x60;limit&#x60; (frozen meaning; core &#x60;LoopState.legacy.new&#x60;).  |  |
| **review** | **kotlin.Int** | Deprecated. Review cards due today within the limits (including overdue). Equals &#x60;loop.ready.review&#x60;. |  |



