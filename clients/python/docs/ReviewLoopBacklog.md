# ReviewLoopBacklog

Due reviews regardless of the review limits.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**beyond_limit** | **int** | &#x60;reviews - ready.review&#x60;: due reviews held back by the limits today. 0 when &#x60;scheduler.reviews_unlimited&#x60;. | 
**reviews** | **int** | Every review due before &#x60;day_ends_at&#x60; that an uncapped (keep-going) build would serve. &#x60;&gt;&#x3D; ready.review&#x60;. | 

## Example

```python
from kotobaya_client.models.review_loop_backlog import ReviewLoopBacklog

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoopBacklog from a JSON string
review_loop_backlog_instance = ReviewLoopBacklog.from_json(json)
# print the JSON string representation of the object
print(ReviewLoopBacklog.to_json())

# convert the object into a dict
review_loop_backlog_dict = review_loop_backlog_instance.to_dict()
# create an instance of ReviewLoopBacklog from a dict
review_loop_backlog_from_dict = ReviewLoopBacklog.from_dict(review_loop_backlog_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


