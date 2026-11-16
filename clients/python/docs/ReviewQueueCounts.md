# ReviewQueueCounts

**Deprecated (ADR-037): use `loop`.** Still sent, with the frozen pre-ADR-037 meaning, for old clients (core `LoopState.legacy`): the normal queue's remaining cards (whole queue, not only `items`; unaffected by `kinds` and by `keep_going`). 

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**learning** | **int** | Deprecated. Cards in learning or relearning due before the day ends, **ready or not**: &#x60;loop.ready.learning + loop.later_today.count&#x60;. This is the over-count ADR-037 replaces (26 here could mean 0 cards to show now); kept unchanged so old clients see what they always saw.  | 
**new** | **int** | Deprecated. New cards still to introduce today: the smaller of the day goal&#39;s remainder and what the decks can supply under their limits, including deck entries not yet materialized (C-27). **Not** bounded by &#x60;limit&#x60; (frozen meaning; core &#x60;LoopState.legacy.new&#x60;).  | 
**review** | **int** | Deprecated. Review cards due today within the limits (including overdue). Equals &#x60;loop.ready.review&#x60;. | 

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


