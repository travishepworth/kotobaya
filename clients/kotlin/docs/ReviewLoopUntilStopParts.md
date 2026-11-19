
# ReviewLoopUntilStopParts

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **later** | **kotlin.Int** | Learning/relearning cards on their timer that come back before &#x60;day_ends_at&#x60;. Always equal to &#x60;later_today.count&#x60;. |  |
| **learning** | **kotlin.Int** | Learning/relearning cards that can be answered now. Always equal to &#x60;ready.learning&#x60;. |  |
| **new** | **kotlin.Int** | New cards still to introduce in this mode. &#x60;keep_going&#x3D;false&#x60;: left to today&#39;s goal (the smaller of the goal&#39;s remainder and what the decks can supply under their limits; kanji cards and deck entries not yet materialized included; not bounded by &#x60;limit&#x60;); 0 once the goal is reached. &#x60;keep_going&#x3D;true&#x60;: the new cards this response drew (about &#x60;limit&#x60;). |  |
| **review** | **kotlin.Int** | Reviews still to answer in this mode: &#x60;ready.review&#x60; for &#x60;keep_going&#x3D;false&#x60;, &#x60;backlog.reviews&#x60; for &#x60;keep_going&#x3D;true&#x60;. |  |



