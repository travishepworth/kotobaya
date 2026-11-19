
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
| **untilStop** | **kotlin.Int** | ADR-038 (additive). Answers left until the stop screen (core &#x60;LoopState.untilStop&#x60;); the review progress bar&#39;s total is &#x60;done + until_stop&#x60; (client rule C3). Each card counts once, so the total grows when a card comes back (Again) or a new card enters its learning steps. - &#x60;keep_going &#x3D; false&#x60;: &#x60;ready.learning + later_today.count + ready.review&#x60; + the new   cards that can still be introduced today (the smaller of the goal&#39;s remainder and   what the decks can supply under their limits; kanji cards served ahead of their   word and deck entries not yet materialized included; **not** bounded by &#x60;limit&#x60;).   These are the answers before &#x60;goal_reached&#x60; / &#x60;done_for_today&#x60; (or &#x60;waiting&#x60; /   &#x60;empty&#x60;). At such a stop screen it equals &#x60;later_today.count&#x60;. - &#x60;keep_going &#x3D; true&#x60;: the stream is endless, so it is what is in sight:   &#x60;ready.learning + later_today.count + backlog.reviews&#x60; + the new cards this   response drew (about &#x60;limit&#x60;; a kanji group may overshoot). Unaffected by &#x60;kinds&#x60;. &#x60;phase &#x3D; reviewing&#x60; implies &#x60;until_stop &gt;&#x3D; 1&#x60;. Sent by every server and local API implementing ADR-038; absent (older) → clients use &#x60;ready.total&#x60;. Examples: 0 ready, 26 later, goal 40 with 0 introduced, 100 new cards → 66. Goal 40 with 10 introduced, 30 reviews due, 5 learning cards on a timer → 65. Both limits reached with 135 reviews beyond the cap → 0; the same with &#x60;keep_going&#x3D;true&amp;limit&#x3D;10&#x60; → 145.  |  [optional] |


<a id="Phase"></a>
## Enum: phase
| Name | Value |
| ---- | ----- |
| phase | reviewing, goal_reached, done_for_today, waiting, empty |



