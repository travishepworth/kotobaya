# ReviewLoopLaterToday

Learning/relearning cards not ready yet that fall due before `day_ends_at`. Never served early. While `phase` is `reviewing` they are only a number; when nothing is served they make the phase `waiting` (ADR-049). A learning card due at or after `day_ends_at` is not counted.

## Properties

Name | Type | Description | Notes
------------ | ------------- | ------------- | -------------
**count** | **int** | How many. | 
**next_due_at** | **datetime** | The earliest &#x60;due_at&#x60; among them; null iff &#x60;count&#x60; is 0. | 

## Example

```python
from kotobaya_client.models.review_loop_later_today import ReviewLoopLaterToday

# TODO update the JSON string below
json = "{}"
# create an instance of ReviewLoopLaterToday from a JSON string
review_loop_later_today_instance = ReviewLoopLaterToday.from_json(json)
# print the JSON string representation of the object
print(ReviewLoopLaterToday.to_json())

# convert the object into a dict
review_loop_later_today_dict = review_loop_later_today_instance.to_dict()
# create an instance of ReviewLoopLaterToday from a dict
review_loop_later_today_from_dict = ReviewLoopLaterToday.from_dict(review_loop_later_today_dict)
```
[[Back to Model list]](../README.md#documentation-for-models) [[Back to API list]](../README.md#documentation-for-api-endpoints) [[Back to README]](../README.md)


