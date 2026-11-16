
# ReviewLoop

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **backlog** | [**ReviewLoopBacklog**](ReviewLoopBacklog.md) |  |  |
| **keepGoing** | **kotlin.Boolean** | Echo of the request&#39;s &#x60;keep_going&#x60; (false when omitted). |  |
| **laterToday** | [**ReviewLoopLaterToday**](ReviewLoopLaterToday.md) |  |  |
| **new** | [**ReviewLoopNew**](ReviewLoopNew.md) |  |  |
| **phase** | [**inline**](#Phase) | Where the loop stands (rule under &#x60;getReviewQueue&#x60; \&quot;Review loop\&quot;; core &#x60;LoopPhase&#x60;). &#x60;reviewing&#x60;: a card is served (or a deck entry awaits materialization). &#x60;goal_reached&#x60; (reviews unlimited) / &#x60;done_for_today&#x60; (review cap on): nothing ready under the limits, Keep going would serve a card. &#x60;waiting&#x60;: nothing ready, nothing more with keep going, a learning card falls due at &#x60;later_today.next_due_at&#x60;. &#x60;empty&#x60;: nothing left today. The set is open for readers: an unknown value is treated as &#x60;reviewing&#x60; when &#x60;items&#x60; is non-empty, else as &#x60;empty&#x60;.  |  |
| **ready** | [**ReviewLoopReady**](ReviewLoopReady.md) |  |  |
| **reviews** | [**ReviewLoopReviews**](ReviewLoopReviews.md) |  |  |


<a id="Phase"></a>
## Enum: phase
| Name | Value |
| ---- | ----- |
| phase | reviewing, goal_reached, done_for_today, waiting, empty |



