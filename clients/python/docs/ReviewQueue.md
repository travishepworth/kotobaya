# ReviewQueue

A prefix of today's review queue.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**counts** | [**ReviewQueueCounts**](ReviewQueueCounts.md) |  | 
**day_ends_at** | **datetime** | Instant the study day rolls over. | 
**generated_at** | **datetime** | When the queue was built. | 
**items** | [**List[QueueItem]**](QueueItem.md) | Cards in the order to show them. | 
**more_available** | **bool** | C-26 (additive). \&quot;Done for today, but Keep going has more.\&quot; True iff **all** of: the request had &#x60;keep_going&#x3D;false&#x60; (or omitted it); &#x60;items&#x60; is empty; and the same request (same &#x60;limit&#x60;, &#x60;deck_id&#x60;, &#x60;kinds&#x60;) with &#x60;keep_going&#x3D;true&#x60; would return a non-empty &#x60;items&#x60;. False otherwise - always with &#x60;keep_going&#x3D;true&#x60;, and whenever &#x60;items&#x60; is non-empty. Independent of &#x60;counts.learning&#x60;. True also when only the per-session new-card cap (&#x60;scheduler.new_per_session&#x60;) is reached: the UI says \&quot;done for now\&quot;, not \&quot;done for today\&quot;. Computed only in the empty case, so it costs a second queue build only then. For a request without &#x60;kinds&#x60; it equals the core&#39;s &#x60;ReviewQueue.moreAvailable&#x60;, which counts drawn deck entries (&#x60;toMaterialize&#x60;) as non-empty (C-27); with &#x60;kinds&#x60; including &#x60;new&#x60;, so does the server&#39;s keep-going test. Servers implementing C-26 always send it; absent (older server) is treated as false.  | [optional] 
**study_day** | **date** | The current study day. | 
**to_materialize** | **int** | C-27 (additive, ADR-032). Deck entries (&#x60;e:&lt;seq&gt;&#x60; without a note) this build drew as new cards that are still not notes: the length of the core&#39;s &#x60;ReviewQueue.toMaterialize&#x60; after the server&#39;s materialization, 0 when &#x60;kinds&#x60; excludes &#x60;new&#x60;. Always 0 for a caller holding &#x60;review&#x60; or &#x60;write&#x60; unless the 3 materialization rounds ran out (then the next fetch continues). For a &#x60;read&#x60;-only caller it is the entries it cannot materialize; they are included in &#x60;counts.new&#x60;. Absent (older server) is treated as 0.  | [optional] 

## Example

```python
from kotobaya_client.models.review_queue import ReviewQueue

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewQueue from a JSON string
review_queue_instance = ReviewQueue.from_json(json)
# print the JSON string representation of the object
print(ReviewQueue.to_json())

# convert the object into a dict
review_queue_dict = review_queue_instance.to_dict()
# create an instance of ReviewQueue from a dict
review_queue_from_dict = ReviewQueue.from_dict(review_queue_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


