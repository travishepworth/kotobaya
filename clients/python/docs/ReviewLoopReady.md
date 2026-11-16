# ReviewLoopReady

Cards that can be shown now, new cards excluded (\"ready now\").

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**learning** | **int** | Learning/relearning cards whose step has elapsed (&#x60;due_at&#x60; &lt;&#x3D; now). Never capped. | 
**review** | **int** | Review cards due before &#x60;day_ends_at&#x60; (overdue included) within the remaining review budget; every such card when &#x60;scheduler.reviews_unlimited&#x60;. At most one per note while siblings are buried.  | 
**total** | **int** | &#x60;learning + review&#x60;. | 

## Example

```python
from kotobaya_client.models.review_loop_ready import ReviewLoopReady

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoopReady from a JSON string
review_loop_ready_instance = ReviewLoopReady.from_json(json)
# print the JSON string representation of the object
print(ReviewLoopReady.to_json())

# convert the object into a dict
review_loop_ready_dict = review_loop_ready_instance.to_dict()
# create an instance of ReviewLoopReady from a dict
review_loop_ready_from_dict = ReviewLoopReady.from_dict(review_loop_ready_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


