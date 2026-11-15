# ReviewQueue

A prefix of today's review queue.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**counts** | [**ReviewQueueCounts**](ReviewQueueCounts.md) |  | 
**day_ends_at** | **datetime** | Instant the study day rolls over. | 
**generated_at** | **datetime** | When the queue was built. | 
**items** | [**List[QueueItem]**](QueueItem.md) | Cards in the order to show them. | 
**more_available** | **bool** | C-26 (additive). \&quot;Done for today, but Keep going has more.\&quot; True iff **all** of: the request had &#x60;keep_going&#x3D;false&#x60; (or omitted it); &#x60;items&#x60; is empty; and the same request (same &#x60;limit&#x60;, &#x60;deck_id&#x60;, &#x60;kinds&#x60;) with &#x60;keep_going&#x3D;true&#x60; would return a non-empty &#x60;items&#x60;. False otherwise - always with &#x60;keep_going&#x3D;true&#x60;, and whenever &#x60;items&#x60; is non-empty. Independent of &#x60;counts.learning&#x60;. Computed only in the empty case, so it costs a second queue build only then. For a request without &#x60;kinds&#x60; it equals the core&#39;s &#x60;ReviewQueue.moreAvailable&#x60; (the server has no pending entries). Servers implementing C-26 always send it; absent (older server) is treated as false.  | [optional] 
**study_day** | **date** | The current study day. | 

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


