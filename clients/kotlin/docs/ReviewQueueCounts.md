
# ReviewQueueCounts

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **learning** | **kotlin.Int** | Deprecated. Cards in learning or relearning due before the day ends, **ready or not**: &#x60;loop.ready.learning + loop.later_today.count&#x60;. This is the over-count ADR-037 replaces (26 here could mean 0 cards to show now); kept unchanged so old clients see what they always saw.  |  |
| **new** | **kotlin.Int** | Deprecated. New cards the normal build drew: its NEW items plus drawn deck entries not yet materialized (&#x60;to_materialize&#x60;, C-27). Since ADR-037 one build draws at most about &#x60;limit&#x60; new cards, so this is bounded by &#x60;limit&#x60; as well as by the day&#39;s remaining goal.  |  |
| **review** | **kotlin.Int** | Deprecated. Review cards due today within the limits (including overdue). Equals &#x60;loop.ready.review&#x60;. |  |



