# ReviewQueueCounts

Cards remaining today under the current limits (whole queue, not only `items`; unaffected by `kinds`). With `keep_going=true` (C-26) they count the keep-going queue instead - `new` is the new cards of this batch (about `limit`), `review` every review due today, `learning` unchanged.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**learning** | **int** | Cards in learning or relearning due before the day ends. | 
**new** | **int** | New cards still to introduce today, including drawn deck entries not yet materialized (&#x60;to_materialize&#x60;, C-27). | 
**review** | **int** | Review cards due today (including overdue). | 

## Example

```python
from kotobaya_client.models.review_queue_counts import ReviewQueueCounts

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewQueueCounts from a JSON string
review_queue_counts_instance = ReviewQueueCounts.from_json(json)
# print the JSON string representation of the object
print(ReviewQueueCounts.to_json())

# convert the object into a dict
review_queue_counts_dict = review_queue_counts_instance.to_dict()
# create an instance of ReviewQueueCounts from a dict
review_queue_counts_from_dict = ReviewQueueCounts.from_dict(review_queue_counts_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


