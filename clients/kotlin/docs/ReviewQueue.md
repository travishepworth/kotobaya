
# ReviewQueue

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **counts** | [**ReviewQueueCounts**](ReviewQueueCounts.md) |  |  |
| **dayEndsAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | Instant the study day rolls over. |  |
| **generatedAt** | [**java.time.OffsetDateTime**](java.time.OffsetDateTime.md) | When the queue was built. |  |
| **items** | [**kotlin.collections.List&lt;QueueItem&gt;**](QueueItem.md) | Cards in the order to show them. |  |
| **studyDay** | [**java.time.LocalDate**](java.time.LocalDate.md) | The current study day. |  |
| **moreAvailable** | **kotlin.Boolean** | C-26 (additive). \&quot;Done for today, but Keep going has more.\&quot; True iff **all** of: the request had &#x60;keep_going&#x3D;false&#x60; (or omitted it); &#x60;items&#x60; is empty; and the same request (same &#x60;limit&#x60;, &#x60;deck_id&#x60;, &#x60;kinds&#x60;) with &#x60;keep_going&#x3D;true&#x60; would return a non-empty &#x60;items&#x60;. False otherwise - always with &#x60;keep_going&#x3D;true&#x60;, and whenever &#x60;items&#x60; is non-empty. Independent of &#x60;counts.learning&#x60;. Computed only in the empty case, so it costs a second queue build only then. For a request without &#x60;kinds&#x60; it equals the core&#39;s &#x60;ReviewQueue.moreAvailable&#x60; (the server has no pending entries). Servers implementing C-26 always send it; absent (older server) is treated as false.  |  [optional] |



