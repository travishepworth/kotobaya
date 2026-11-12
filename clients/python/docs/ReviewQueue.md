# ReviewQueue

A prefix of today's review queue.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**counts** | [**ReviewQueueCounts**](ReviewQueueCounts.md) |  | 
**day_ends_at** | **datetime** | Instant the study day rolls over. | 
**generated_at** | **datetime** | When the queue was built. | 
**items** | [**List[QueueItem]**](QueueItem.md) | Cards in the order to show them. | 
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


