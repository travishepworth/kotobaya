# ReviewLoopNew

Today's new cards against the goal.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**available** | **bool** | A new card (or a deck entry that would become one) exists that keep going could introduce, ignoring the goal and per-deck new limits. | 
**goal** | **int** | &#x60;scheduler.new_per_day&#x60;. | 
**today** | **int** | Cards introduced in the current study day (first review of a new card; undone ones excluded). May exceed &#x60;goal&#x60; after keep going. | 

## Example

```python
from kotobaya_client.models.review_loop_new import ReviewLoopNew

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoopNew from a JSON string
review_loop_new_instance = ReviewLoopNew.from_json(json)
# print the JSON string representation of the object
print(ReviewLoopNew.to_json())

# convert the object into a dict
review_loop_new_dict = review_loop_new_instance.to_dict()
# create an instance of ReviewLoopNew from a dict
review_loop_new_from_dict = ReviewLoopNew.from_dict(review_loop_new_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


